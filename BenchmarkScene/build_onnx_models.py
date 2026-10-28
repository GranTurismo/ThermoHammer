#!/usr/bin/env python3
"""
The Windows app's ONNX models for the AI test: each of the six 8-bit TFLite models (the Android app's
assets/ai — Qualcomm AI Hub's w8a8 builds, which the iPhone also runs through TFLite and, converted op for
op by build_coreml_models.py, through Core ML) rewritten op for op as an ONNX graph, because Windows has no
TFLite runtime. ONNX Runtime runs them: its CPU provider (int8 QLinear kernels), DirectML (GPU) and the QNN
provider (Qualcomm NPUs).

Nothing is retrained or re-quantized. Two variants of every model go to ThermoHammerWindows/Assets/ai/:

  <name>.q.onnx    INT8 QDQ, for the CPU and QNN providers. The weights are TFLite's int8 values with TFLite's
                   per-channel scales (int8 initializers → DequantizeLinear, per-channel axis 0; the bias is
                   int32 with scale input_scale × weight_scale, as TFLite stores it). Every tensor TFLite
                   keeps in 8 bits goes through QuantizeLinear → DequantizeLinear with TFLite's own scale and
                   zero point, so the graph is the pattern ONNX Runtime fuses into QLinearConv / QLinearAdd /
                   QLinearMul / QGemm / QLinearSigmoid / QLinearConcat ... and QNN's HTP takes.
  <name>.f16.onnx  float16, for DirectML: the same graph with the weights dequantized and the quantize pairs
                   dropped (what the iPhone's TFLite Metal delegate does: FP16, weights dequantized); the
                   fused activations and every clamp that is an op stay.
  <name>.json      what the app needs to feed and decode both: names, shapes, layouts, dtypes, quantization.

Both variants have the same interface (the same names, so one runner serves them):
  input  "x"      the TFLite input tensor's 8-bit dtype (uint8 for all six models), planar NCHW (1×C×H×W), the
                  values TFLite's input tensor holds (the model dequantizes them with the input scale and zero
                  point itself)
  output "out<k>" float32, TFLite's output k in TFLite's own layout and element order (NHWC for images,
                  [1, 8400, 4] for YOLOX's boxes ...): the dequantized values (q - zero) × scale, so
                  q = round(v / scale) + zero gives TFLite's 8-bit tensor back exactly (q variant), or the
                  float result (f16 variant). An output TFLite keeps without quantization parameters (YOLOX's
                  class ids) is float32 holding the integer.

The graphs differ from a literal op-for-op copy only where the values are the same: a PAD that feeds a VALID
convolution becomes the convolution's pads (the zero point's real value is 0.0, the padding ONNX applies), MEAN over
H and W is GlobalAveragePool, MINIMUM/MAXIMUM against one number is a Clip, Real-ESRGAN's reshape/transpose pixel shuffle is one DepthToSpace (only where
the chain is proved to move the elements the same way), and tensors are laid out NCHW (NHWC only
where TFLite's reshapes need it; a tensor is transposed once and kept, and outputs come out NHWC).

    uv venv /tmp/onnxenv --python 3.12 && uv pip install --python /tmp/onnxenv/bin/python ai-edge-litert onnx onnxruntime numpy pillow
    /tmp/onnxenv/bin/python BenchmarkScene/build_onnx_models.py [--check [--ref]] [--bench] [--act uint8] [models...]

--check runs every model with TFLite (this Mac, XNNPACK as on the phones) and with ONNX Runtime (CPU) on the same
inputs (the benchmark's own photos and random 8-bit noise) and prints how far apart the outputs are, in steps of the
output's quantization grid (q variant) or as a relative error and correlation (f16 variant), what the stage would show
(top classes, detections, class map, PSNR ...), and every op of the q graph on TFLite's own input tensors (so the
rounding differences of a hundred layers don't add up: each op must come within 1 step). TFLite's own kernels differ
from each other by as much as ONNX Runtime's differ from XNNPACK; --ref prints that too (TFLite's reference kernels
against XNNPACK).
--bench prints ONNX Runtime's inferences per second (4 CPU threads) and which QLinear kernels it fused.
--act uint8 puts TFLite's int8 activations on the same grid as uint8 (zero point + 128): the same values, and the
unsigned type the ONNX Runtime x86-64 and QNN guidance recommends for activations (the default keeps TFLite's int8).
"""
import argparse
import collections
import json
import os
import sys
import tempfile
import time

import numpy as np
import onnx
from onnx import TensorProto as TP
from onnx import helper as oh
from onnx import numpy_helper as nh
from ai_edge_litert import schema_py_generated as tfl

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "ThermoHammerAndroid/app/src/main/assets/ai")
MEDIA = os.path.join(ROOT, "ThermoHammerWindows/Assets/media")
OUT = os.path.join(ROOT, "ThermoHammerWindows/Assets/ai")
MODELS = ["mobilenet_v2", "yolox", "ffnet_40s", "real_esrgan_general_x4v3", "midas", "yamnet"]
OPSET = 17
IR_VERSION = 8                            # ONNX Runtime 1.12 and later read it

OPS = {v: k for k, v in vars(tfl.BuiltinOperator).items() if not k.startswith("_")}
NP = {tfl.TensorType.FLOAT32: np.float32, tfl.TensorType.INT32: np.int32, tfl.TensorType.UINT8: np.uint8,
      tfl.TensorType.INT8: np.int8, tfl.TensorType.INT64: np.int64, tfl.TensorType.INT16: np.int16}
TFL_NAME = {tfl.TensorType.FLOAT32: "float32", tfl.TensorType.INT32: "int32", tfl.TensorType.UINT8: "uint8",
            tfl.TensorType.INT8: "int8", tfl.TensorType.INT64: "int64"}
ONNX_T = {tfl.TensorType.UINT8: TP.UINT8, tfl.TensorType.INT8: TP.INT8, tfl.TensorType.INT32: TP.INT32,
          tfl.TensorType.INT64: TP.INT64, tfl.TensorType.FLOAT32: TP.FLOAT}
TO_NCHW = [0, 3, 1, 2]
TO_NHWC = [0, 2, 3, 1]
AXIS = {0: 0, 1: 2, 2: 3, 3: 1}          # an NHWC axis → the same axis of the NCHW layout


class Model:
    """A TFLite file: its first subgraph, constant tensors and quantization."""

    def __init__(self, path):
        with open(path, "rb") as f:
            buf = f.read()
        self.m = tfl.ModelT.InitFromObj(tfl.Model.GetRootAs(buf, 0))
        self.g = self.m.subgraphs[0]

    def op(self, op):
        c = self.m.operatorCodes[op.opcodeIndex]
        return OPS[max(c.builtinCode, c.deprecatedBuiltinCode)]

    def t(self, i):
        return self.g.tensors[i]

    def shape(self, i):
        return [int(s) for s in self.t(i).shape]

    def const(self, i):
        """A constant tensor's raw values, or None if it is computed."""
        t = self.t(i)
        data = self.m.buffers[t.buffer].data if t.buffer < len(self.m.buffers) else None
        if data is None or len(data) == 0:
            return None
        return np.frombuffer(bytes(bytearray(data)), dtype=NP[t.type]).reshape(self.shape(i))

    def quant(self, i):
        """(scales, zero points, axis) of an 8-bit tensor, or None."""
        t = self.t(i)
        q = t.quantization
        if t.type not in (tfl.TensorType.INT8, tfl.TensorType.UINT8) or q is None or q.scale is None or len(q.scale) == 0:
            return None
        s = np.asarray(q.scale, dtype=np.float64)
        if not np.all(s > 0):
            return None
        z = np.asarray(q.zeroPoint if q.zeroPoint is not None else [0] * len(s), dtype=np.int64)
        return s, z, q.quantizedDimension

    def real(self, i):
        """A constant's real values (8-bit and int32 constants dequantized)."""
        v = self.const(i)
        t = self.t(i)
        q = t.quantization
        if t.type in (tfl.TensorType.INT8, tfl.TensorType.UINT8, tfl.TensorType.INT32) and q is not None and q.scale is not None and len(q.scale):
            s = np.asarray(q.scale, dtype=np.float64)
            z = np.asarray(q.zeroPoint if q.zeroPoint is not None else [0] * len(s), dtype=np.float64)
            shape = [1] * v.ndim
            if len(s) > 1:
                shape[q.quantizedDimension] = len(s)
            return (v.astype(np.float64) - z.reshape(shape)) * s.reshape(shape)
        return v.astype(np.float64)


class Graph:
    """An ONNX graph under construction: nodes, initializers, unique names."""

    def __init__(self):
        self.nodes = []
        self.inits = []
        self.names = set()
        self.shared = {}

    def name(self, base):
        n, k = base, 0
        while n in self.names:
            k += 1
            n = f"{base}_{k}"
        self.names.add(n)
        return n

    def const(self, arr, base="c", share=True):
        arr = np.asarray(arr)
        arr = np.ascontiguousarray(arr) if arr.ndim else arr        # (ascontiguousarray would make a scalar 1-D)
        key = (arr.dtype.str, arr.shape, arr.tobytes()) if share and arr.size <= 64 else None
        if key in self.shared:
            return self.shared[key]
        n = self.name(base)
        self.inits.append(nh.from_array(arr, n))
        if key is not None:
            self.shared[key] = n
        return n

    def op(self, kind, inputs, n_out=1, **attrs):
        outs = [self.name(kind.lower()) for _ in range(n_out)]
        self.nodes.append(oh.make_node(kind, list(inputs), outs, name=self.name(kind + "_node"), **attrs))
        return outs[0] if n_out == 1 else outs

    def prune(self, outputs):
        """Drops the nodes and initializers no output depends on."""
        need = set(outputs)
        keep = []
        for n in reversed(self.nodes):
            if any(o in need for o in n.output):
                keep.append(n)
                need.update(i for i in n.input if i)
        self.nodes = keep[::-1]
        self.inits = [t for t in self.inits if t.name in need]


class Converter:
    """One TFLite model → one ONNX graph, in `mode` "q" (int8 QDQ, float32 between the pairs) or "f16" (float16)."""

    def __init__(self, model, mode, act="int8"):
        assert mode in ("q", "f16", "f32") and act in ("int8", "uint8")        # f32: the float graph in float32, to tell float16's error from the quantization's
        self.m = model
        self.q = mode == "q"
        self.u8 = act == "uint8"
        self.ft = np.float16 if mode == "f16" else np.float32        # the float type between the pairs
        self.ftp = TP.FLOAT16 if mode == "f16" else TP.FLOAT
        self.g = Graph()
        self.v = {}            # tensor index → {"nchw" | "plain": float var} (4-D tensors may exist in both)
        self.fold = {}         # PAD output → (its input, [top, left, bottom, right]) when the Conv after it takes the padding
        self.cons = collections.defaultdict(list)
        for op in model.g.operators:
            for i in op.inputs:
                if i >= 0:
                    self.cons[i].append(op)
        self.outputs = set(model.g.outputs)
        self.skip = set()      # ops (by id) another op's conversion has taken over
        self.d2s = {}          # first op (by id) of a reshape/transpose chain that is a pixel shuffle → (input, output, block size)
        self.find_depth_to_space()

    def find_depth_to_space(self):
        """Real-ESRGAN's pixel shuffle is reshape → transpose → reshape → transpose → reshape. Where that chain provably moves the
        elements the way ONNX's DepthToSpace (CRD) does (checked on an index tensor), the one op replaces it: the same values, five
        copies fewer, and one op a Qualcomm NPU can run."""
        ops = self.m.g.operators
        names = [self.m.op(o) for o in ops]
        for k in range(len(ops) - 4):
            chain = ops[k:k + 5]
            if names[k:k + 5] != ["RESHAPE", "TRANSPOSE", "RESHAPE", "TRANSPOSE", "RESHAPE"]:
                continue
            if not all(chain[j].outputs[0] == chain[j + 1].inputs[0] and len(self.cons[chain[j].outputs[0]]) == 1
                       and chain[j].outputs[0] not in self.outputs for j in range(4)):
                continue
            src, dst = chain[0].inputs[0], chain[4].outputs[0]
            if self.rank(src) != 4 or self.rank(dst) != 4 or not self.same_grid(src, dst):
                continue
            (n, h, w, c), (n2, h2, w2, c2) = self.m.shape(src), self.m.shape(dst)
            r = h2 // h
            if n != 1 or n2 != 1 or h2 != h * r or w2 != w * r or c != c2 * r * r:
                continue
            y = np.arange(c * h * w).reshape(1, h, w, c)
            want = y.reshape(1, h, w, c2, r, r).transpose(0, 1, 4, 2, 5, 3).reshape(1, h2, w2, c2)
            for op, nm in zip(chain, names[k:k + 5]):
                y = y.reshape(self.m.shape(op.outputs[0])) if nm == "RESHAPE" else y.transpose([int(p) for p in self.m.const(op.inputs[1])])
            if np.array_equal(y, want):
                self.d2s[id(chain[0])] = (src, dst, r)
                self.skip.update(id(op) for op in chain[1:])

    # ── quantization ──

    def rank(self, i):
        return len(self.m.shape(i))

    def grid(self, i):
        """(scale, zero point, numpy dtype) of 8-bit tensor i on the grid the graph uses, or None."""
        q = self.m.quant(i)
        if q is None:
            return None
        s, z, _ = q
        assert len(s) == 1, f"tensor {i}: per-channel activation"
        if self.m.t(i).type == tfl.TensorType.INT8:
            if self.u8:
                return np.float32(s[0]), np.uint8(z[0] + 128), np.uint8
            return np.float32(s[0]), np.int8(z[0]), np.int8
        return np.float32(s[0]), np.uint8(z[0]), np.uint8

    def requant(self, var, i):
        """var on tensor i's quantization grid: QuantizeLinear → DequantizeLinear (q variant, 8-bit tensors only)."""
        gr = self.grid(i) if self.q else None
        if gr is None:
            return var
        s, z, _ = gr
        sn, zn = self.g.const(s, "scale"), self.g.const(z, "zp")
        return self.g.op("DequantizeLinear", [self.g.op("QuantizeLinear", [var, sn, zn]), sn, zn])

    def same_grid(self, a, b):
        qa, qb = self.m.quant(a), self.m.quant(b)
        return qa is not None and qb is not None and np.allclose(qa[0], qb[0]) and np.array_equal(qa[1], qb[1])

    # ── values and layouts ──

    def get(self, i, want):
        """Tensor i as a float var, in "nchw" or "plain" (TFLite's own) layout; 4-D tensors convert on demand."""
        if i not in self.v:
            return self.const_value(i, want)
        if self.rank(i) != 4:
            want = "plain"
        d = self.v[i]
        if want not in d:
            other = "plain" if want == "nchw" else "nchw"
            t = self.g.op("Transpose", [d[other]], perm=TO_NCHW if want == "nchw" else TO_NHWC)
            d[want] = self.requant(t, i)
        return d[want]

    def absorbs(self, i, fused):
        """Whether tensor i's grid already holds the fused activation's clamp (ReLU: zero point at the 8-bit minimum), so
        QuantizeLinear alone does it and ONNX Runtime drops the Relu/Clip."""
        gr = self.grid(i)
        if gr is None or fused == tfl.ActivationFunctionType.NONE:
            return True
        s, z, dt = gr
        lo, hi = np.iinfo(dt).min, np.iinfo(dt).max
        if fused == tfl.ActivationFunctionType.RELU:
            return int(z) == lo
        if fused == tfl.ActivationFunctionType.RELU6:
            return int(z) == lo and (hi - lo) * float(s) <= 6 * (1 + 1e-5)
        return False

    def put(self, i, var, layout, fused=tfl.ActivationFunctionType.NONE):
        """Stores tensor i: the fused activation, then TFLite's quantization grid (q variant). Where the grid can't take the
        clamp over (YAMNet's depthwise convolutions: ReLU with the zero point mid-range) the pair comes first, so the
        convolution still ends in a QuantizeLinear and ONNX Runtime fuses it into QLinearConv; ReLU after it clamps to the
        zero point, which is what TFLite's fused clamp to [quantized 0, max] does."""
        if self.q and not self.absorbs(i, fused):
            var = self.requant(var, i)
        self.v[i] = {layout if self.rank(i) == 4 else "plain": self.requant(self.act(var, fused), i)}

    def pick(self, ins, out):
        """The layout for an op over 4-D tensors that costs the fewest transposes ("plain" when the result is an output)."""
        if self.rank(out) != 4:
            return "plain"
        cost = {"nchw": 0, "plain": 0}
        for i in ins:
            if i in self.v and self.rank(i) == 4:
                for layout in cost:
                    cost[layout] += layout not in self.v[i]
        if out in self.outputs:
            cost["nchw"] += 1
        return "plain" if cost["plain"] < cost["nchw"] else "nchw"

    def const_value(self, i, want):
        """A constant used as data (concatenated ...): float, in the wanted layout."""
        v = self.m.real(i)
        if v.ndim == 4 and want == "nchw":
            v = v.transpose(TO_NCHW)
        return self.g.const(v.astype(self.ft), "const")

    def operand(self, i, layout, four):
        """An elementwise operand. Constants broadcast the TFLite way (right-aligned), then go into NCHW beside an NCHW tensor;
        8-bit constants stay int8 initializers → DequantizeLinear (the QDQ form of a quantized constant)."""
        if i in self.v:
            return self.get(i, layout)
        raw, real = self.m.const(i), self.m.real(i)

        def shaped(a):
            if a.size == 1:
                return a.reshape(())
            if four:
                a = a.reshape([1] * (4 - a.ndim) + list(a.shape))
                if layout == "nchw":
                    a = a.transpose(TO_NCHW)
            return np.ascontiguousarray(a)

        gr = self.grid(i) if self.q else None
        q = self.m.quant(i)
        if gr is not None and len(q[0]) == 1:
            s, z, dt = gr
            data = raw.astype(np.int16) + (128 if self.u8 and raw.dtype == np.int8 else 0)
            sn, zn = self.g.const(s, "scale"), self.g.const(z, "zp")
            return self.g.op("DequantizeLinear", [self.g.const(shaped(data).astype(dt), "qconst", share=False), sn, zn])
        return self.g.const(shaped(real).astype(self.ft), "const", share=False)

    def act(self, var, fused):
        A = tfl.ActivationFunctionType
        if fused == A.RELU:
            return self.g.op("Relu", [var])
        if fused == A.RELU6:
            return self.g.op("Clip", [var, self.g.const(np.array(0, self.ft)), self.g.const(np.array(6, self.ft))])
        if fused == A.RELU_N1_TO_1:
            return self.g.op("Clip", [var, self.g.const(np.array(-1, self.ft)), self.g.const(np.array(1, self.ft))])
        if fused == A.TANH:
            return self.g.op("Tanh", [var])
        assert fused == A.NONE, f"activation {fused}"
        return var

    def weight(self, i, perm):
        """A filter transposed by `perm`: int8 initializer → DequantizeLinear with TFLite's scales (q), or float16 (f16)."""
        q = self.m.quant(i)
        if not self.q or q is None:
            return self.g.const(self.m.real(i).transpose(perm).astype(self.ft), "w", share=False)
        s, z, axis = q
        w = np.ascontiguousarray(self.m.const(i).transpose(perm))
        if len(s) > 1:
            args = [w, s.astype(np.float32), z.astype(w.dtype)]
            attrs = {"axis": perm.index(axis)}
        else:
            args = [w, np.float32(s[0]), w.dtype.type(z[0])]
            attrs = {}
        return self.g.op("DequantizeLinear", [self.g.const(a, "w" if k == 0 else "ws", share=False) for k, a in enumerate(args)], **attrs)

    def bias(self, bi, xi, wi, n):
        """The bias: int32 → DequantizeLinear with scale input_scale × weight_scale (TFLite's definition; q), or float16."""
        if bi < 0:
            return None
        if not self.q or self.m.quant(xi) is None or self.m.quant(wi) is None:
            return self.g.const(self.m.real(bi).reshape(-1).astype(self.ft), "b", share=False)
        sx = self.m.quant(xi)[0][0]
        sw = np.broadcast_to(self.m.quant(wi)[0], (n,))
        scale = (sx * sw).astype(np.float32)
        stored = np.asarray(self.m.t(bi).quantization.scale, dtype=np.float64)
        assert np.allclose(stored, scale, rtol=1e-3), "bias scale is not input_scale × weight_scale"
        b = self.m.const(bi).reshape(-1).astype(np.int32)
        return self.g.op("DequantizeLinear", [self.g.const(b, "b", share=False), self.g.const(scale, "bs", share=False),
                                              self.g.const(np.zeros(n, np.int32), "bz", share=False)], axis=0)

    @staticmethod
    def same_pads(n_h, n_w, k_h, k_w, s_h, s_w, d_h, d_w):
        """TFLite's SAME padding as ONNX pads [top, left, bottom, right] (the odd pixel goes to the end)."""
        pads = []
        for n, k, s, d in ((n_h, k_h, s_h, d_h), (n_w, k_w, s_w, d_w)):
            total = max((-(-n // s) - 1) * s + (k - 1) * d + 1 - n, 0)
            pads.append((total // 2, total - total // 2))
        return [pads[0][0], pads[1][0], pads[0][1], pads[1][1]]

    # ── ops ──

    def convert(self, op):
        name = self.m.op(op)
        ins, outs = list(op.inputs), list(op.outputs)
        o = op.builtinOptions
        out = outs[0]
        rank_in = self.rank(ins[0]) if ins and ins[0] >= 0 else 0
        g = self.g
        P = tfl.Padding

        if id(op) in self.d2s:
            src, dst, r = self.d2s[id(op)]
            self.put(dst, g.op("DepthToSpace", [self.get(src, "nchw")], blocksize=r, mode="CRD"), "nchw")

        elif name in ("CONV_2D", "DEPTHWISE_CONV_2D"):
            src, ext = self.fold.get(ins[0], (ins[0], [0, 0, 0, 0]))     # a PAD folded into this convolution
            x = self.get(src, "nchw")
            _, h, w, cin = self.m.shape(src)
            _, kh, kw, _ = self.m.shape(ins[1])
            if name == "CONV_2D":
                wv, groups, cout = self.weight(ins[1], [0, 3, 1, 2]), 1, self.m.shape(ins[1])[0]       # OHWI → OIHW
            else:
                assert o.depthMultiplier == 1
                wv, groups, cout = self.weight(ins[1], [3, 0, 1, 2]), cin, self.m.shape(ins[1])[3]     # 1HWC → C1HW
            pads = self.same_pads(h, w, kh, kw, o.strideH, o.strideW, o.dilationHFactor, o.dilationWFactor) \
                if o.padding == P.SAME else [0, 0, 0, 0]
            pads = [a + b for a, b in zip(pads, ext)]
            b = self.bias(ins[2] if len(ins) > 2 else -1, src, ins[1], cout)
            y = g.op("Conv", [x, wv] + ([b] if b else []), kernel_shape=[kh, kw], strides=[o.strideH, o.strideW], pads=pads,
                     dilations=[o.dilationHFactor, o.dilationWFactor], group=groups)
            self.put(out, y, "nchw", o.fusedActivationFunction)

        elif name == "FULLY_CONNECTED":
            x = self.get(ins[0], "plain")
            shape = self.m.shape(ins[0])
            k = shape[-1]
            if len(shape) != 2:
                x = g.op("Reshape", [x, g.const(np.array([int(np.prod(shape[:-1])), k], np.int64))])
            n = self.m.shape(ins[1])[0]
            b = self.bias(ins[2] if len(ins) > 2 else -1, ins[0], ins[1], n)
            y = g.op("Gemm", [x, self.weight(ins[1], [0, 1])] + ([b] if b else []), transB=1)
            out_shape = self.m.shape(out)
            if len(out_shape) != 2:
                y = g.op("Reshape", [y, g.const(np.array(out_shape, np.int64))])
            self.put(out, y, "plain", o.fusedActivationFunction)

        elif name in ("ADD", "SUB", "MUL", "MINIMUM", "MAXIMUM"):
            fused = getattr(o, "fusedActivationFunction", tfl.ActivationFunctionType.NONE) if o is not None else 0
            computed = [i for i in ins if i in self.v]
            four = self.rank(out) == 4
            layout = self.pick(computed, out) if four else "plain"
            if any(self.rank(i) != 4 for i in computed):
                layout = "plain"
            kinds = {"ADD": "Add", "SUB": "Sub", "MUL": "Mul", "MINIMUM": "Min", "MAXIMUM": "Max"}
            const = [i for i in ins if i not in self.v]
            if name in ("MINIMUM", "MAXIMUM") and len(computed) == 1 and const and self.m.real(const[0]).size == 1:
                # min/max against one number is a Clip (ONNX Runtime fuses it into the quantize step when the grid already holds it)
                c = g.const(np.array(float(self.m.real(const[0]).reshape(-1)[0]), self.ft))
                x = self.get(computed[0], layout)
                y = g.op("Clip", [x, "", c] if name == "MINIMUM" else [x, c])
            else:
                a, b = self.operand(ins[0], layout, four), self.operand(ins[1], layout, four)
                y = g.op(kinds[name], [a, b])
            self.put(out, y, layout, fused)

        elif name in ("RELU", "RELU6", "LOGISTIC", "EXP", "TANH"):
            layout = self.pick([ins[0]], out)
            x = self.get(ins[0], layout)
            if name == "RELU6":
                y = self.act(x, tfl.ActivationFunctionType.RELU6)
            else:
                y = g.op({"RELU": "Relu", "LOGISTIC": "Sigmoid", "EXP": "Exp", "TANH": "Tanh"}[name], [x])
            self.put(out, y, layout)

        elif name == "QUANTIZE":
            layout = self.pick([ins[0]], out)
            self.put(out, self.get(ins[0], layout), layout)              # the new grid is applied by put()

        elif name == "PRELU":
            slope = self.operand(ins[1], "nchw", True)
            self.put(out, g.op("PRelu", [self.get(ins[0], "nchw"), slope]), "nchw")

        elif name == "PAD":
            p = self.m.const(ins[1]).astype(np.int64)                    # [[before, after]] per NHWC axis
            use = self.cons[out]
            if (rank_in == 4 and not p[0].any() and not p[3].any() and len(use) == 1 and out not in self.outputs
                    and self.m.op(use[0]) in ("CONV_2D", "DEPTHWISE_CONV_2D") and use[0].inputs[0] == out
                    and use[0].builtinOptions.padding == P.VALID and self.same_grid(ins[0], out)):
                # the zero padding (the zero point's real value, 0.0) goes into the Conv's pads: the same values, one tensor less
                self.fold[out] = (ins[0], [int(p[1][0]), int(p[2][0]), int(p[1][1]), int(p[2][1])])
                return
            layout = self.pick([ins[0]], out)
            if layout == "nchw":
                p = p[TO_NCHW]
            y = g.op("Pad", [self.get(ins[0], layout), g.const(np.concatenate([p[:, 0], p[:, 1]]).astype(np.int64))])
            self.put(out, y, layout)

        elif name == "MAX_POOL_2D":
            _, h, w, _ = self.m.shape(ins[0])
            pads = self.same_pads(h, w, o.filterHeight, o.filterWidth, o.strideH, o.strideW, 1, 1) if o.padding == P.SAME else [0] * 4
            y = g.op("MaxPool", [self.get(ins[0], "nchw")], kernel_shape=[o.filterHeight, o.filterWidth],
                     strides=[o.strideH, o.strideW], pads=pads)
            self.put(out, y, "nchw", o.fusedActivationFunction)

        elif name in ("RESIZE_NEAREST_NEIGHBOR", "RESIZE_BILINEAR"):
            x = self.get(ins[0], "nchw")
            n, h, w, c = self.m.shape(ins[0])
            nh_, nw_ = [int(v) for v in self.m.const(ins[1])]
            align, half = bool(o.alignCorners), bool(o.halfPixelCenters)
            sizes = g.const(np.array([n, c, nh_, nw_], np.int64))
            if name == "RESIZE_NEAREST_NEIGHBOR":
                # TFLite: in = (half ? out + 0.5 : out) × scale (align_corners: (in-1)/(out-1)), then floor (round when aligned)
                if align:
                    attrs = dict(coordinate_transformation_mode="align_corners", nearest_mode="round_prefer_ceil")
                elif half:
                    attrs = dict(coordinate_transformation_mode="half_pixel", nearest_mode="round_prefer_ceil")
                else:
                    attrs = dict(coordinate_transformation_mode="asymmetric", nearest_mode="floor")
                y = g.op("Resize", [x, "", "", sizes], mode="nearest", **attrs)
            else:
                mode = "align_corners" if align else "half_pixel" if half else "asymmetric"
                y = g.op("Resize", [x, "", "", sizes], mode="linear", coordinate_transformation_mode=mode)
            self.put(out, y, "nchw")

        elif name == "CONCATENATION":
            layout = self.pick(ins, out)
            axis = o.axis % self.rank(out)
            y = g.op("Concat", [self.get(i, layout) for i in ins], axis=AXIS[axis] if layout == "nchw" else axis)
            self.put(out, y, layout, o.fusedActivationFunction)

        elif name in ("MEAN", "REDUCE_MAX"):
            axes = sorted(int(a) % rank_in for a in np.atleast_1d(self.m.const(ins[1])))
            keep = bool(o.keepDims)
            kind = "ReduceMean" if name == "MEAN" else "ReduceMax"
            if rank_in == 4 and (keep or axes == [1, 2]):                 # NCHW keeps the remaining axes in TFLite's order
                x = self.get(ins[0], "nchw")
                ax = sorted(AXIS[a] for a in axes)
                if name == "MEAN" and ax == [2, 3]:
                    y = g.op("GlobalAveragePool", [x])
                    if not keep:
                        y = g.op("Reshape", [y, g.const(np.array(self.m.shape(out), np.int64))])
                else:
                    y = g.op(kind, [x], axes=ax, keepdims=int(keep))
                self.put(out, y, "nchw" if keep else "plain")
            else:
                self.put(out, g.op(kind, [self.get(ins[0], "plain")], axes=axes, keepdims=int(keep)), "plain")

        elif name == "ARG_MAX":
            assert rank_in != 4
            axis = int(np.atleast_1d(self.m.const(ins[1]))[0]) % rank_in
            y = g.op("ArgMax", [self.get(ins[0], "plain")], axis=axis, keepdims=0, select_last_index=0)
            if self.m.t(out).type == tfl.TensorType.INT32:
                y = g.op("Cast", [y], to=TP.INT32)
            self.put(out, y, "plain")

        elif name == "CAST":
            t = self.m.t(out).type
            to = self.ftp if t == tfl.TensorType.FLOAT32 else ONNX_T[t]
            layout = "nchw" if "nchw" in self.v[ins[0]] and self.rank(ins[0]) == 4 else "plain"
            self.put(out, g.op("Cast", [self.get(ins[0], layout)], to=to), layout)

        elif name == "RESHAPE":
            y = g.op("Reshape", [self.get(ins[0], "plain"), g.const(np.array(self.m.shape(out), np.int64))])
            self.put(out, y, "plain")

        elif name == "TRANSPOSE":
            perm = [int(p) for p in self.m.const(ins[1])]
            self.put(out, g.op("Transpose", [self.get(ins[0], "plain")], perm=perm), "plain")

        elif name == "STRIDED_SLICE":
            begin, end, stride = (np.atleast_1d(self.m.const(i)).astype(np.int64) for i in ins[1:4])
            shape = self.m.shape(ins[0])
            assert not o.ellipsisMask and not o.newAxisMask and (stride > 0).all()
            sq = [bool(o.shrinkAxisMask >> k & 1) for k in range(rank_in)]
            layout = "plain" if any(sq) else self.pick([ins[0]], out)
            starts, stops, steps, axes = [], [], [], []
            for k in range(rank_in):
                n, s = shape[k], int(stride[k])
                b = 0 if o.beginMask >> k & 1 else int(begin[k])
                if sq[k]:
                    b = b + n if b < 0 else b
                    e = b + 1
                else:
                    e = n if o.endMask >> k & 1 else int(end[k])
                start, stop, step = slice(b, e, s).indices(n)
                if (start, stop, step) != (0, n, 1):
                    starts.append(start); stops.append(stop); steps.append(step)
                    axes.append(AXIS[k] if layout == "nchw" else k)
            x = self.get(ins[0], layout)
            y = g.op("Slice", [x] + [g.const(np.array(a, np.int64)) for a in (starts, stops, axes, steps)]) if axes else x
            if any(sq):
                y = g.op("Reshape", [y, g.const(np.array(self.m.shape(out), np.int64))])
            self.put(out, y, layout)

        else:
            raise NotImplementedError(name)

    # ── the whole graph ──

    def run(self, name):
        m, g = self.m, self.g
        (inp,) = m.g.inputs
        in_shape = m.shape(inp)                                   # NHWC
        planar = [in_shape[0], in_shape[3], in_shape[1], in_shape[2]]
        in_type = m.t(inp).type
        s, z, _ = m.quant(inp)
        if self.q:
            x = g.op("DequantizeLinear", ["x", g.const(np.float32(s[0]), "scale"), g.const(NP[in_type](z[0]), "zp")])
        else:
            x = g.op("Cast", ["x"], to=self.ftp)
            if z[0] != 0:
                x = g.op("Sub", [x, g.const(np.array(z[0], self.ft))])
            x = g.op("Mul", [x, g.const(np.array(s[0], self.ft))])
        self.v[inp] = {"nchw": x}
        for op in m.g.operators:
            if id(op) not in self.skip:
                self.convert(op)

        outputs, out_meta = [], []
        for k, o in enumerate(m.g.outputs):
            d = self.v[o]
            if self.rank(o) == 4 and "plain" not in d:
                var = g.op("Transpose", [d["nchw"]], perm=TO_NHWC)   # outputs only: the values are already on the grid
            else:
                var = d["plain"]
            if m.t(o).type != tfl.TensorType.FLOAT32 and m.quant(o) is None or self.ft == np.float16:
                var = g.op("Cast", [var], to=TP.FLOAT)
            var = g.op("Identity", [var])
            g.nodes[-1].output[0] = f"out{k}"
            outputs.append(oh.make_tensor_value_info(f"out{k}", TP.FLOAT, m.shape(o)))
            q = m.quant(o)
            out_meta.append({"name": f"out{k}", "dtype": "float32", "layout": "NHWC" if self.rank(o) == 4 else "row-major, TFLite's order",
                             "shape": m.shape(o), "tflite_dtype": TFL_NAME[m.t(o).type],
                             "scale": float(q[0][0]) if q else 0.0, "zero": int(q[1][0]) if q else 0})
        g.prune([f"out{k}" for k in range(len(outputs))])
        graph = oh.make_graph(g.nodes, f"{name}_{'q' if self.q else 'f16' if self.ft == np.float16 else 'f32'}", [oh.make_tensor_value_info("x", ONNX_T[in_type], planar)],
                              outputs, initializer=g.inits)
        model = oh.make_model(graph, opset_imports=[oh.make_opsetid("", OPSET)], producer_name="thermohammer")
        model.ir_version = IR_VERSION
        return model, planar, out_meta


def assert_static(model):
    """Every tensor of the graph has a fixed shape (batch 1)."""
    inferred = onnx.shape_inference.infer_shapes(model, strict_mode=True, data_prop=True)
    for vi in list(inferred.graph.value_info) + list(inferred.graph.input) + list(inferred.graph.output):
        dims = vi.type.tensor_type.shape.dim
        assert all(d.HasField("dim_value") and d.dim_value > 0 for d in dims), f"{vi.name}: dynamic shape"
    assert inferred.graph.input[0].type.tensor_type.shape.dim[0].dim_value == 1


def build(name, act):
    m = Model(os.path.join(ASSETS, name + ".tflite"))
    (inp,) = m.g.inputs
    s, z, _ = m.quant(inp)
    models = {}
    for mode in ("q", "f16"):
        models[mode], planar, out_meta = Converter(m, mode, act).run(name)
    meta = {
        "model": name, "source": name + ".tflite", "opset": OPSET,
        "input": {"name": "x", "dtype": TFL_NAME[m.t(inp).type], "layout": "NCHW", "shape": planar, "tflite_shape": m.shape(inp),
                  "tflite_layout": "NHWC", "scale": float(s[0]), "zero": int(z[0]),
                  "note": "TFLite's 8-bit input tensor, transposed from NHWC to planar NCHW; the models dequantize it themselves"},
        "outputs": out_meta,
        "output_note": "float32, TFLite's layout and order; value = (q - zero) * scale, q = round(value / scale) + zero. "
                       "scale 0: not quantized in TFLite, the float32 holds the integer",
        "variants": {
            "q": {"file": name + ".q.onnx", "providers": ["CPU", "QNN"], "activations": f"{'uint8' if act == 'uint8' else 'int8'} QDQ on "
                  "TFLite's grids, int8 per-channel weights, int32 bias"},
            "f16": {"file": name + ".f16.onnx", "providers": ["DirectML"], "activations": "float16, weights dequantized, no quantize pairs"},
        },
        # the same names and keys as the Core ML models' metadata
        "input_shape": m.shape(inp), "input_scale": float(s[0]), "input_zero": int(z[0]),
    }
    os.makedirs(OUT, exist_ok=True)
    sizes = {}
    for mode, model in models.items():
        assert_static(model)
        onnx.checker.check_model(model, full_check=True)
        model.metadata_props.add(key="thermohammer", value=json.dumps(meta))
        model.doc_string = f"{name}: Qualcomm AI Hub w8a8 TFLite model, converted op for op ({mode}; ThermoHammer AI test)"
        path = os.path.join(OUT, f"{name}.{mode}.onnx")
        onnx.save(model, path)
        sizes[mode] = os.path.getsize(path)
    with open(os.path.join(OUT, name + ".json"), "w") as f:
        json.dump(meta, f, indent=2)
        f.write("\n")
    return meta, sizes


# ── checking ──────────────────────────────────────────────────────────────────

def session(path, optimized=None, level=None):
    import onnxruntime as ort
    so = ort.SessionOptions()
    so.intra_op_num_threads = 4
    so.inter_op_num_threads = 1
    if optimized:
        so.optimized_model_filepath = optimized
    if level is not None:
        so.graph_optimization_level = level
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def inputs_for(name, shape):
    """8-bit inputs (NHWC): the benchmark's own photos prepared like the apps do, and noise."""
    outs = []
    try:
        from PIL import Image
        lake = Image.open(os.path.join(MEDIA, "photo_lake.jpg")).convert("RGB")
        city = Image.open(os.path.join(MEDIA, "photo_city.jpg")).convert("RGB")
        lake, city = (im.resize((im.width // 2, im.height // 2), Image.BILINEAR) for im in (lake, city))

        def square(im):
            s = min(im.size)
            return im.crop(((im.width - s) // 2, (im.height - s) // 2, (im.width + s) // 2, (im.height + s) // 2))

        if name == "mobilenet_v2":
            img = square(lake).resize((224, 224), Image.BILINEAR)
        elif name == "yolox":
            img = Image.new("RGB", (640, 640), (0x72,) * 3)
            img.paste(city.resize((640, 480), Image.BILINEAR), (0, 80))
        elif name == "ffnet_40s":
            img = city.crop((0, city.height // 6, city.width, city.height // 6 + city.width // 2)).resize((2048, 1024), Image.BILINEAR)
        elif name == "real_esrgan_general_x4v3":
            img = city.resize((1000, 750), Image.BILINEAR).crop((720, 330, 848, 458))
        elif name == "midas":
            img = square(city).resize((256, 256), Image.BILINEAR)
        else:
            img = None
        if img is not None:
            outs.append(("photo", np.asarray(img, dtype=np.uint8).reshape(shape)))
    except Exception as e:                                             # no Pillow / photos: noise only
        print(f"  (no photo input: {e})")
    if name == "yamnet":
        outs.append(("spectrogram", np.frombuffer(open(os.path.join(ASSETS, "yamnet_input.bin"), "rb").read(), dtype=np.uint8).reshape(shape)))
    rng = np.random.default_rng(1)
    for k in range(2):
        outs.append((f"noise{k}", rng.integers(0, 256, size=shape, dtype=np.uint8)))
    return outs


def iou(a, b):
    inter = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
    return inter / max(1e-6, (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter)


def detections(boxes, scores, classes):
    """What the stage keeps of YOLOX's candidates: score > 0.35, best first, NMS (same class, IoU 0.45), at most 25."""
    b, sc, cl = boxes.reshape(-1, 4), scores.reshape(-1), classes.reshape(-1)
    kept = []
    for i in (i for i in np.argsort(-sc, kind="stable") if sc[i] > 0.35):
        if len(kept) < 25 and not any(cl[k] == cl[i] and iou(b[i], b[k]) > 0.45 for k in kept):
            kept.append(i)
    return [(int(cl[i]), b[i]) for i in kept]


def agreement(name, ref, got):
    """What the two runtimes' outputs (real values) would show on the stage."""
    if name in ("mobilenet_v2", "yamnet"):
        a, b = (list(np.argsort(-v[0].reshape(-1), kind="stable")[:5]) for v in (ref, got))
        return f"top-1 {'same' if a[0] == b[0] else 'DIFFERENT'}, top-5 overlap {len(set(a) & set(b))}/5"
    if name == "yolox":
        a, b = detections(*ref), detections(*got)
        return f"{len(a)} detections vs {len(b)}, {sum(any(c == c2 and iou(x, y) > 0.5 for c2, y in b) for c, x in a)} matched (same class, IoU > 0.5)"
    if name == "ffnet_40s":
        return f"class map: {(ref[0].argmax(-1) == got[0].argmax(-1)).mean() * 100:.2f}% of the pixels agree"
    if name == "real_esrgan_general_x4v3":
        mse = np.mean((np.clip(ref[0], 0, 1) - np.clip(got[0], 0, 1)) ** 2)
        return f"PSNR {10 * np.log10(1 / max(mse, 1e-12)):.1f} dB between the two upscaled pictures"
    return f"depth map correlation {np.corrcoef(ref[0].reshape(-1), got[0].reshape(-1))[0, 1]:.4f}"


def check(name, meta, ref=False):
    """ONNX Runtime (CPU) on both variants against TFLite (XNNPACK) on the same inputs."""
    from ai_edge_litert.interpreter import Interpreter, OpResolverType
    path = os.path.join(ASSETS, name + ".tflite")
    it = Interpreter(model_path=path, num_threads=4)
    it.allocate_tensors()
    itr = None
    if ref:
        itr = Interpreter(model_path=path, num_threads=4, experimental_op_resolver_type=OpResolverType.BUILTIN_REF)
        itr.allocate_tensors()
    d = it.get_input_details()[0]
    sq = session(os.path.join(OUT, name + ".q.onnx"))
    sf = session(os.path.join(OUT, name + ".f16.onnx"))
    for label, q in inputs_for(name, d["shape"]):
        it.set_tensor(d["index"], q)
        it.invoke()
        planar = np.ascontiguousarray(q.transpose(0, 3, 1, 2))
        got_q = sq.run(None, {"x": planar})
        got_f = sf.run(None, {"x": planar})
        if itr is not None:
            itr.set_tensor(itr.get_input_details()[0]["index"], q)
            itr.invoke()
        reals = []
        for k, od in enumerate(it.get_output_details()):
            o = meta["outputs"][k]
            sc, zr = o["scale"], o["zero"]
            tq = it.get_tensor(od["index"]).astype(np.float64)
            reals.append((tq - zr) * sc if sc > 0 else tq)
            line = f"  {name:>26} {label:<11} out{k} {str(o['shape']):<18}"
            if sc > 0:
                lo, hi = (0, 255) if od["dtype"] == np.uint8 else (-128, 127)
                v = np.clip(np.round(got_q[k].astype(np.float64) / sc) + zr, lo, hi)
                diff = np.abs(v - tq)
                line += f" q: max {diff.max():.0f} steps, mean {diff.mean():.4f}, {(diff <= 1).mean() * 100:.2f}% within 1"
            else:
                line += f" q: {(got_q[k] == tq).mean() * 100:.2f}% equal"
            a, b = got_f[k].astype(np.float64).reshape(-1), reals[k].reshape(-1)
            if sc > 0:
                rel = np.linalg.norm(a - b) / max(np.linalg.norm(b), 1e-12)
                corr = np.corrcoef(a, b)[0, 1] if a.std() > 0 and b.std() > 0 else float("nan")
                line += f" | f16: rel L2 {rel:.4f}, corr {corr:.5f}, max {np.abs(a - b).max() / sc:.1f} steps"
            else:
                line += f" | f16: {(a == b).mean() * 100:.2f}% equal"
            if itr is not None:
                rf = itr.get_tensor(itr.get_output_details()[k]["index"]).astype(np.float64)
                line += f" | tflite reference kernels vs xnnpack: max {np.abs(rf - tq).max():.0f}"
            print(line)
        if label in ("photo", "spectrogram"):
            versions = [("q  ", got_q), ("f16", got_f)]
            if itr is not None:
                versions.append(("tflite reference kernels", [(itr.get_tensor(od["index"]).astype(np.float64) - meta["outputs"][k]["zero"])
                                                              * (meta["outputs"][k]["scale"] or 1) for k, od in enumerate(itr.get_output_details())]))
            for tag, got in versions:
                print(f"  {name:>26} {label:<11} {tag} vs xnnpack: {agreement(name, reals, [np.asarray(v, np.float64) for v in got])}")


def check_ops(name, act):
    """Every op of the q graph on TFLite's own input tensors (its reference kernels keep every tensor), against TFLite's output
    tensor of that op: the arithmetic of each op without the rounding differences of a hundred layers adding up."""
    import onnxruntime as ort
    from ai_edge_litert.interpreter import Interpreter, OpResolverType
    m = Model(os.path.join(ASSETS, name + ".tflite"))
    c = Converter(m, "q", act)
    model, planar, _ = c.run(name)
    it = Interpreter(model_path=os.path.join(ASSETS, name + ".tflite"), num_threads=4, experimental_preserve_all_tensors=True,
                     experimental_op_resolver_type=OpResolverType.BUILTIN_REF)
    it.allocate_tensors()
    d = it.get_input_details()[0]
    q = inputs_for(name, d["shape"])[0][1]
    it.set_tensor(d["index"], q)
    it.invoke()
    g = model.graph
    feeds, exposed = {"x": np.ascontiguousarray(q.transpose(0, 3, 1, 2))}, {}
    for i, layouts in c.v.items():
        qq = m.quant(i)
        if qq is None:
            if m.t(i).type in (tfl.TensorType.INT32, tfl.TensorType.UINT8):          # ArgMax / Cast: compared as they are
                g.output.append(oh.make_tensor_value_info(layouts["plain"], ONNX_T[m.t(i).type], None))
                exposed[i] = layouts["plain"]
            continue
        for layout, var in layouts.items():
            ref = (it.get_tensor(i).astype(np.float64) - qq[1][0]) * qq[0][0]
            ref = ref.transpose(0, 3, 1, 2) if layout == "nchw" else ref
            for n in g.node:                                 # every consumer reads TFLite's tensor instead of ours
                n.input[:] = ["tf_" + var if v == var else v for v in n.input]
            g.input.append(oh.make_tensor_value_info("tf_" + var, TP.FLOAT, list(ref.shape)))
            feeds["tf_" + var] = np.ascontiguousarray(ref.astype(np.float32))
            g.output.append(oh.make_tensor_value_info(var, TP.FLOAT, None))
            exposed[(i, layout)] = var
    so = ort.SessionOptions()
    so.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
    s = ort.InferenceSession(model.SerializeToString(), so, providers=["CPUExecutionProvider"])
    res = dict(zip([o.name for o in s.get_outputs()], s.run(None, feeds)))
    worst, exact = collections.defaultdict(float), 1.0
    for op in m.g.operators:
        i = op.outputs[0]
        if i in exposed:
            exact = min(exact, float((res[exposed[i]] == it.get_tensor(i)).mean()))
            continue
        qq = m.quant(i)
        if qq is None or i not in c.v:
            continue
        layout, var = next(iter(c.v[i].items()))
        v = res[var].transpose(0, 2, 3, 1) if layout == "nchw" else res[var]
        worst[m.op(op)] = max(worst[m.op(op)], float(np.abs(np.round(v / qq[0][0]) + qq[1][0] - it.get_tensor(i)).max()))
    print(f"  {name:>26} per op, on TFLite's own inputs (steps, worst tensor of each op type; ArgMax/Cast equal: {exact * 100:.0f}%): "
          + ", ".join(f"{k} {v:.0f}" for k, v in worst.items()))


def bench(name, seconds=1.5, rounds=3):
    """ONNX Runtime's CPU speed (4 threads) on the q variant, and what it fused. Best of a few windows: the Mac's clocks wander."""
    from ai_edge_litert.interpreter import Interpreter
    path = os.path.join(OUT, name + ".q.onnx")
    opt = os.path.join(tempfile.mkdtemp(), name + ".opt.onnx")
    s = session(path, optimized=opt)
    ops = collections.Counter(n.op_type for n in onnx.load(opt).graph.node)
    src = collections.Counter(n.op_type for n in onnx.load(path).graph.node)
    fused = {k: v for k, v in sorted(ops.items()) if k.startswith("QLinear") or k in ("QGemm", "QuantizeLinear", "DequantizeLinear", "Conv", "FusedConv", "Gemm", "MatMul")}
    x = np.random.default_rng(0).integers(0, 256, size=s.get_inputs()[0].shape, dtype=np.uint8)

    def ips(run):
        for _ in range(3):
            run()
        best = 0
        for _ in range(rounds):
            n, t0 = 0, time.perf_counter()
            while time.perf_counter() - t0 < seconds:
                run()
                n += 1
            best = max(best, n / (time.perf_counter() - t0))
        return best

    it = Interpreter(model_path=os.path.join(ASSETS, name + ".tflite"), num_threads=4)
    it.allocate_tensors()
    d = it.get_input_details()[0]
    it.set_tensor(d["index"], x.transpose(0, 2, 3, 1).copy())
    ref = ips(it.invoke)
    got = ips(lambda: s.run(None, {"x": x}))
    print(f"  {name:>26}: ORT int8 {got:8.2f} ips   (TFLite XNNPACK, same Mac: {ref:8.2f})   QuantizeLinear/DequantizeLinear in the file: "
          f"{src['QuantizeLinear'] + src['DequantizeLinear']}, after ORT: {fused}")
    return got


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true")
    ap.add_argument("--bench", action="store_true")
    ap.add_argument("--ref", action="store_true", help="--check also compares TFLite's reference kernels with XNNPACK")
    ap.add_argument("--act", choices=["int8", "uint8"], default="int8")
    ap.add_argument("models", nargs="*", default=MODELS)
    args = ap.parse_args()
    for name in args.models:
        meta, sizes = build(name, args.act)
        print(f"{name}: {os.path.join(OUT, name)}.{{q.onnx {sizes['q'] / 1e6:.1f} MB, f16.onnx {sizes['f16'] / 1e6:.1f} MB, json}}")
        if args.check:
            check(name, meta, args.ref)
            check_ops(name, args.act)
        if args.bench:
            bench(name)


if __name__ == "__main__":
    sys.exit(main())

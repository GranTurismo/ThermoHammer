#!/usr/bin/env python3
"""
The iPhone's Neural Engine models for the AI test: each of the six 8-bit TFLite models (the Android app's
assets/ai — Qualcomm AI Hub's w8a8 builds, which the iPhone also runs on its CPU and GPU through TFLite)
rewritten op for op as a Core ML program, because Core ML is the only way onto Apple's Neural Engine and its
TFLite delegate takes float models only.

Nothing is retrained or re-quantized: the weights stay the same int8 values with the same per-channel scales
(stored compressed, expanded on load), and every tensor TFLite keeps in 8 bits goes through a quantize →
dequantize pair with TFLite's own scale and zero point (Core ML's W8A8 form, which the Neural Engine runs as
8-bit where it can) — except in YOLOX, whose activations stay float16: with them quantized the Neural Engine
compiler hands most of the network to the CPU (2.6× slower on an M5), whatever part of it is quantized alone.
Tensors are laid out NCHW inside; the input is planar (1×C×H×W) and the outputs come back
in TFLite's layout, as float16 values on TFLite's quantization grid. The models' quantization parameters ride
along in the metadata so the app can feed and read them like the TFLite files.

    python3 -m venv env && env/bin/pip install ai-edge-litert coremltools numpy
    env/bin/python BenchmarkScene/build_coreml_models.py [--check]

--check runs every model on this Mac (Core ML, CPU) and with TFLite on random inputs and prints how far apart
the outputs are, in steps of the output's quantization grid.
"""
import argparse
import json
import os
import sys

import numpy as np
import coremltools as ct
from coremltools.converters.mil import Builder as mb
from coremltools.converters.mil.mil import types
from ai_edge_litert import schema_py_generated as tfl

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "ThermoHammerAndroid/app/src/main/assets/ai")
OUT = os.path.join(ROOT, "CpuThrottlingTest/Benchmark/AI/NeuralEngine")
MODELS = ["mobilenet_v2", "yolox", "ffnet_40s", "real_esrgan_general_x4v3", "midas", "yamnet"]
FLOAT_ACTIVATIONS = {"yolox"}             # see above

OPS = {v: k for k, v in vars(tfl.BuiltinOperator).items() if not k.startswith("_")}
NP = {tfl.TensorType.FLOAT32: np.float32, tfl.TensorType.INT32: np.int32, tfl.TensorType.UINT8: np.uint8,
      tfl.TensorType.INT8: np.int8, tfl.TensorType.INT64: np.int64, tfl.TensorType.INT16: np.int16}
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


def f16(a):
    return np.asarray(a, dtype=np.float16)


class Converter:
    def __init__(self, model, quantize_activations=True):
        self.m = model
        self.quantize_activations = quantize_activations
        self.v = {}            # tensor index → (MIL var, "nchw" | "plain")

    # ── values ──

    def nchw(self, i):
        """Tensor i as an NCHW var (it must be 4-D)."""
        var, layout = self.get(i, "nchw")
        return var

    def plain(self, i):
        """Tensor i in TFLite's own layout."""
        var, layout = self.get(i, "plain")
        return var

    def get(self, i, want):
        if i in self.v:
            var, layout = self.v[i]
            if layout == want or len(self.m.shape(i)) != 4:
                return var, layout
            return mb.transpose(x=var, perm=TO_NCHW if want == "nchw" else TO_NHWC), want
        v = self.m.real(i)
        if v.ndim == 4 and want == "nchw":
            return mb.const(val=f16(v.transpose(TO_NCHW))), "nchw"
        return mb.const(val=f16(v)), "plain"

    def operand(self, i, like_rank4_nchw):
        """An elementwise operand: constants broadcast the TFLite way, then into NCHW beside an NCHW tensor."""
        if i in self.v or not like_rank4_nchw:
            return self.get(i, "nchw" if like_rank4_nchw else "plain")[0]
        v = self.m.real(i)
        if v.ndim == 0 or v.size == 1:
            return mb.const(val=f16(v.reshape(())))
        v = v.reshape([1] * (4 - v.ndim) + list(v.shape))
        return mb.const(val=f16(v.transpose(TO_NCHW)))

    def put(self, i, var, layout, raw=False):
        """Stores tensor i; an 8-bit tensor first goes through TFLite's quantization (unless `raw`: values copied as they are)."""
        q = self.m.quant(i)
        if q is not None and not raw and self.quantize_activations:
            s, z, _ = q
            dtype = "uint8" if self.m.t(i).type == tfl.TensorType.UINT8 else "int8"
            zp = np.uint8(z[0]) if dtype == "uint8" else np.int8(z[0])
            var = mb.quantize(input=var, scale=np.float16(s[0]), zero_point=zp, output_dtype=dtype)
            var = mb.dequantize(input=var, scale=np.float16(s[0]), zero_point=zp)
        self.v[i] = (var, layout)

    def same_grid(self, a, b):
        qa, qb = self.m.quant(a), self.m.quant(b)
        return qa is not None and qb is not None and np.allclose(qa[0], qb[0]) and np.array_equal(qa[1], qb[1])

    def act(self, var, fused):
        if fused == tfl.ActivationFunctionType.RELU:
            return mb.relu(x=var)
        if fused == tfl.ActivationFunctionType.RELU6:
            return mb.relu6(x=var)
        if fused == tfl.ActivationFunctionType.RELU_N1_TO_1:
            return mb.clip(x=var, alpha=np.float16(-1), beta=np.float16(1))
        assert fused == tfl.ActivationFunctionType.NONE, f"activation {fused}"
        return var

    def weight(self, i, perm):
        """An 8-bit filter, transposed by `perm`, kept as int8/uint8 with its scales (expanded on load)."""
        q = self.m.quant(i)
        w = self.m.const(i)
        if q is None:
            return mb.const(val=f16(self.m.real(i).transpose(perm)))
        s, z, axis = q
        w = np.ascontiguousarray(w.transpose(perm))
        axis = perm.index(axis) if len(s) > 1 else 0
        zero = z.astype(w.dtype)
        return mb.constexpr_affine_dequantize(quantized_data=w, zero_point=zero if len(s) > 1 else zero[0],
                                             scale=f16(s) if len(s) > 1 else np.float16(s[0]), axis=axis)

    def bias(self, i, n):
        return f16(np.zeros(n) if i < 0 else self.m.real(i).reshape(-1))

    # ── ops ──

    def convert(self, op):
        name = self.m.op(op)
        ins, outs = list(op.inputs), list(op.outputs)
        o = op.builtinOptions
        out = outs[0]
        rank_in = len(self.m.shape(ins[0])) if ins and ins[0] >= 0 else 0
        pad = {tfl.Padding.SAME: "same", tfl.Padding.VALID: "valid"}

        if name in ("CONV_2D", "DEPTHWISE_CONV_2D"):
            x = self.nchw(ins[0])
            if name == "CONV_2D":
                w = self.weight(ins[1], [0, 3, 1, 2])           # OHWI → OIHW
                groups = 1
            else:
                w = self.weight(ins[1], [3, 0, 1, 2])           # 1HWC → C1HW
                groups = self.m.shape(ins[0])[3]
            cout = self.m.shape(ins[1])[0 if name == "CONV_2D" else 3]
            y = mb.conv(x=x, weight=w, bias=self.bias(ins[2] if len(ins) > 2 else -1, cout), strides=[o.strideH, o.strideW],
                        pad_type=pad[o.padding], dilations=[o.dilationHFactor, o.dilationWFactor], groups=groups)
            self.put(out, self.act(y, o.fusedActivationFunction), "nchw")

        elif name == "FULLY_CONNECTED":
            x = self.plain(ins[0])
            shape = self.m.shape(ins[0])
            if len(shape) != 2:
                x = mb.reshape(x=x, shape=[-1, shape[-1]])
            w = self.weight(ins[1], [0, 1])
            y = mb.linear(x=x, weight=w, bias=self.bias(ins[2] if len(ins) > 2 else -1, self.m.shape(ins[1])[0]))
            out_shape = self.m.shape(out)
            if len(out_shape) != 2:
                y = mb.reshape(x=y, shape=out_shape)
            self.put(out, self.act(y, o.fusedActivationFunction), "plain")

        elif name in ("ADD", "SUB", "MUL", "MINIMUM", "MAXIMUM"):
            four = any(len(self.m.shape(i)) == 4 and (i in self.v) for i in ins) and len(self.m.shape(out)) == 4
            a, b = self.operand(ins[0], four), self.operand(ins[1], four)
            fn = {"ADD": mb.add, "SUB": mb.sub, "MUL": mb.mul, "MINIMUM": mb.minimum, "MAXIMUM": mb.maximum}[name]
            y = fn(x=a, y=b)
            fused = getattr(o, "fusedActivationFunction", tfl.ActivationFunctionType.NONE) if o is not None else 0
            self.put(out, self.act(y, fused), "nchw" if four else "plain")

        elif name in ("RELU", "RELU6", "LOGISTIC", "EXP", "TANH"):
            var, layout = self.get(ins[0], "nchw" if rank_in == 4 else "plain")
            fn = {"RELU": mb.relu, "RELU6": mb.relu6, "LOGISTIC": mb.sigmoid, "EXP": mb.exp, "TANH": mb.tanh}[name]
            self.put(out, fn(x=var), layout)

        elif name == "QUANTIZE":
            var, layout = self.get(ins[0], "nchw" if rank_in == 4 else "plain")
            self.put(out, var, layout)

        elif name == "PRELU":
            x = self.nchw(ins[0])
            alpha = self.m.real(ins[1]).reshape(-1)
            self.put(out, mb.prelu(x=x, alpha=f16(alpha)), "nchw")

        elif name == "PAD":
            p = self.m.const(ins[1]).astype(np.int64)      # [[before, after]] per NHWC axis
            if rank_in == 4:
                p = p[TO_NCHW]
                x = self.nchw(ins[0])
            else:
                x = self.plain(ins[0])
            y = mb.pad(x=x, pad=p.reshape(-1).astype(np.int32), mode="constant", constant_val=np.float16(0))
            self.put(out, y, "nchw" if rank_in == 4 else "plain", raw=self.same_grid(ins[0], out))

        elif name == "MAX_POOL_2D":
            y = mb.max_pool(x=self.nchw(ins[0]), kernel_sizes=[o.filterHeight, o.filterWidth],
                            strides=[o.strideH, o.strideW], pad_type=pad[o.padding])
            self.put(out, self.act(y, o.fusedActivationFunction), "nchw", raw=self.same_grid(ins[0], out))

        elif name in ("RESIZE_NEAREST_NEIGHBOR", "RESIZE_BILINEAR"):
            x = self.nchw(ins[0])
            h, w = self.m.shape(ins[0])[1:3]
            nh, nw = [int(v) for v in self.m.const(ins[1])]
            whole = nh % h == 0 and nw % w == 0
            if name == "RESIZE_NEAREST_NEIGHBOR":
                assert whole and not o.alignCorners, "nearest resize by a whole factor only"
                y = mb.upsample_nearest_neighbor(x=x, scale_factor_height=nh // h, scale_factor_width=nw // w)
                self.put(out, y, "nchw", raw=self.same_grid(ins[0], out))
            else:
                if whole and not o.alignCorners:
                    y = mb.upsample_bilinear(x=x, scale_factor_height=float(nh // h), scale_factor_width=float(nw // w),
                                             align_corners=False, half_pixel_centers=bool(o.halfPixelCenters))
                else:
                    mode = "STRICT_ALIGN_CORNERS" if o.alignCorners else "UNALIGN_CORNERS" if o.halfPixelCenters else "DEFAULT"
                    y = mb.resize_bilinear(x=x, target_size_height=nh, target_size_width=nw, sampling_mode=mode)
                self.put(out, y, "nchw")

        elif name == "CONCATENATION":
            axis = o.axis % len(self.m.shape(out))
            four = len(self.m.shape(out)) == 4
            xs = [self.get(i, "nchw" if four else "plain")[0] for i in ins]
            y = mb.concat(values=xs, axis=AXIS[axis] if four else axis)
            self.put(out, self.act(y, o.fusedActivationFunction), "nchw" if four else "plain")

        elif name == "MEAN" or name == "REDUCE_MAX":
            axes = sorted(int(a) % rank_in for a in np.atleast_1d(self.m.const(ins[1])))
            keep = bool(o.keepDims)
            fn = mb.reduce_mean if name == "MEAN" else mb.reduce_max
            if rank_in == 4 and (keep or axes == [1, 2]):          # NCHW keeps the remaining axes in TFLite's order
                y = fn(x=self.nchw(ins[0]), axes=sorted(AXIS[a] for a in axes), keep_dims=keep)
                self.put(out, y, "nchw" if keep else "plain")
            else:
                self.put(out, fn(x=self.plain(ins[0]), axes=axes, keep_dims=keep), "plain")

        elif name == "ARG_MAX":
            axis = int(np.atleast_1d(self.m.const(ins[1]))[0]) % rank_in
            assert rank_in != 4
            self.put(out, mb.reduce_argmax(x=self.plain(ins[0]), axis=axis, keep_dims=False), "plain")

        elif name == "CAST":
            target = {tfl.TensorType.INT32: "int32", tfl.TensorType.FLOAT32: "fp16", tfl.TensorType.UINT8: "fp16",
                      tfl.TensorType.INT8: "fp16"}[self.m.t(out).type]
            var, layout = self.get(ins[0], "plain")
            self.put(out, mb.cast(x=var, dtype=target), layout, raw=True)

        elif name == "RESHAPE":
            y = mb.reshape(x=self.plain(ins[0]), shape=self.m.shape(out))
            if len(self.m.shape(out)) == 4:
                self.put(out, mb.transpose(x=y, perm=TO_NCHW), "nchw", raw=self.same_grid(ins[0], out))
            else:
                self.put(out, y, "plain", raw=self.same_grid(ins[0], out))

        elif name == "TRANSPOSE":
            perm = [int(p) for p in self.m.const(ins[1])]
            y = mb.transpose(x=self.plain(ins[0]), perm=perm)
            if len(self.m.shape(out)) == 4:
                y = mb.transpose(x=y, perm=TO_NCHW)
            self.put(out, y, "nchw" if len(self.m.shape(out)) == 4 else "plain", raw=self.same_grid(ins[0], out))

        elif name == "STRIDED_SLICE":
            begin, end, stride = (np.atleast_1d(self.m.const(i)).astype(np.int64) for i in ins[1:4])
            bm = [bool(o.beginMask >> k & 1) for k in range(rank_in)]
            em = [bool(o.endMask >> k & 1) for k in range(rank_in)]
            sq = [bool(o.shrinkAxisMask >> k & 1) for k in range(rank_in)]
            assert not o.ellipsisMask and not o.newAxisMask
            if rank_in == 4 and not any(sq):
                order = TO_NCHW
                y = mb.slice_by_index(x=self.nchw(ins[0]), begin=begin[order].astype(np.int32), end=end[order].astype(np.int32),
                                      stride=stride[order].astype(np.int32), begin_mask=[bm[k] for k in order],
                                      end_mask=[em[k] for k in order])
                self.put(out, y, "nchw", raw=self.same_grid(ins[0], out))
            else:
                y = mb.slice_by_index(x=self.plain(ins[0]), begin=begin.astype(np.int32), end=end.astype(np.int32),
                                      stride=stride.astype(np.int32), begin_mask=bm, end_mask=em, squeeze_mask=sq)
                four = len(self.m.shape(out)) == 4
                self.put(out, mb.transpose(x=y, perm=TO_NCHW) if four else y, "nchw" if four else "plain",
                         raw=self.same_grid(ins[0], out))

        else:
            raise NotImplementedError(name)


def build(name):
    m = Model(os.path.join(ASSETS, name + ".tflite"))
    g = m.g
    (inp,) = g.inputs
    in_shape = m.shape(inp)                       # NHWC
    planar = [in_shape[0], in_shape[3], in_shape[1], in_shape[2]]
    meta = {"input_shape": in_shape, "outputs": [], "activations": "float16" if name in FLOAT_ACTIVATIONS else "int8"}
    q = m.quant(inp)
    meta["input_scale"], meta["input_zero"] = (float(q[0][0]), int(q[1][0])) if q else (1.0, 0)
    for o in g.outputs:
        q = m.quant(o)
        meta["outputs"].append({"shape": m.shape(o), "scale": float(q[0][0]) if q else 0.0, "zero": int(q[1][0]) if q else 0})

    @mb.program(input_specs=[mb.TensorSpec(shape=planar, dtype=types.fp16)], opset_version=ct.target.iOS17)
    def prog(x):
        c = Converter(m, quantize_activations=name not in FLOAT_ACTIVATIONS)
        c.v[inp] = (x, "nchw")                    # the app feeds TFLite's input values, dequantized
        for op in g.operators:
            c.convert(op)
        results = []
        for k, o in enumerate(g.outputs):
            var = c.plain(o)
            if var.dtype != types.fp16:
                var = mb.cast(x=var, dtype="fp16")
            results.append(mb.identity(x=var, name=f"out{k}"))
        return tuple(results)

    model = ct.convert(prog, convert_to="mlprogram", minimum_deployment_target=ct.target.iOS17,
                       compute_precision=ct.precision.FLOAT16)
    spec = model.get_spec()
    model = ct.models.MLModel(spec, weights_dir=model.weights_dir)
    model.short_description = f"{name}: Qualcomm AI Hub w8a8 TFLite model, converted op for op (ThermoHammer AI test)"
    model.user_defined_metadata["thermohammer"] = json.dumps(meta)
    os.makedirs(OUT, exist_ok=True)
    path = os.path.join(OUT, name + ".mlpackage")
    model.save(path)
    return model, meta


def check(name, model, meta):
    """Core ML on this Mac's CPU against TFLite, both on the same random 8-bit input."""
    from ai_edge_litert.interpreter import Interpreter
    it = Interpreter(model_path=os.path.join(ASSETS, name + ".tflite"))
    it.allocate_tensors()
    d = it.get_input_details()[0]
    rng = np.random.default_rng(1)
    q = rng.integers(0, 256, size=d["shape"], dtype=np.uint8) if d["dtype"] == np.uint8 else rng.integers(-128, 128, size=d["shape"], dtype=np.int8)
    if name == "yamnet":
        q = np.frombuffer(open(os.path.join(ASSETS, "yamnet_input.bin"), "rb").read(), dtype=np.uint8).reshape(d["shape"])
    it.set_tensor(d["index"], q)
    it.invoke()
    x = ((q.astype(np.float32) - meta["input_zero"]) * meta["input_scale"]).transpose(TO_NCHW).astype(np.float16)
    cpu = ct.models.MLModel(os.path.join(OUT, name + ".mlpackage"), compute_units=ct.ComputeUnit.CPU_ONLY)
    got = cpu.predict({"x": x})
    for k, od in enumerate(it.get_output_details()):
        ref = it.get_tensor(od["index"]).astype(np.float64)
        o = meta["outputs"][k]
        v = np.asarray(got[f"out{k}"], dtype=np.float64)
        if o["scale"] > 0:
            v = np.clip(np.round(v / o["scale"]) + o["zero"], 0 if od["dtype"] == np.uint8 else -128, 255 if od["dtype"] == np.uint8 else 127)
        diff = np.abs(v.reshape(ref.shape) - ref)
        print(f"  {name} out{k} {list(ref.shape)}: mean |Δ| {diff.mean():.3f}, max {diff.max():.0f} steps, "
              f"{(diff <= 1).mean() * 100:.1f}% within 1")


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", action="store_true")
    ap.add_argument("models", nargs="*", default=MODELS)
    args = ap.parse_args()
    for name in args.models:
        model, meta = build(name)
        print(f"{name}: {os.path.join(OUT, name + '.mlpackage')}")
        if args.check:
            check(name, model, meta)


if __name__ == "__main__":
    sys.exit(main())

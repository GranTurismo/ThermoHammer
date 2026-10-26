// The OpenCL entry points llama.cpp's GPU backend calls, resolved at run time from the phone's own driver
// (libOpenCL.so is a vendor library, not part of the NDK), so libbenchllm.so also loads where there is none: then
// no platform is found and the LLM test runs on the CPU only. The same library paths as LiteRT's GPU delegate.
#define CL_TARGET_OPENCL_VERSION 300
#include <CL/cl.h>

#include <dlfcn.h>
#include <pthread.h>
#include <stddef.h>

static void* lib;
static pthread_once_t once = PTHREAD_ONCE_INIT;

static void open_driver(void) {
    static const char* const paths[] = {
        "libOpenCL.so", "/system/vendor/lib64/libOpenCL.so", "/vendor/lib64/libOpenCL.so",
        "/system/lib64/libOpenCL.so", "libOpenCL-pixel.so", "libOpenCL-car.so",
    };
    for (size_t i = 0; i < sizeof paths / sizeof *paths && !lib; i++) lib = dlopen(paths[i], RTLD_NOW | RTLD_LOCAL);
}

static void* resolve(const char* name) {
    pthread_once(&once, open_driver);
    return lib ? dlsym(lib, name) : NULL;
}

typedef cl_int (*clGetPlatformIDs_fn)(cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms);
CL_API_ENTRY cl_int CL_API_CALL clGetPlatformIDs(cl_uint num_entries, cl_platform_id * platforms, cl_uint * num_platforms) {
    static clGetPlatformIDs_fn fn;
    if (!fn) fn = (clGetPlatformIDs_fn) resolve("clGetPlatformIDs");
    if (!fn) {
        if (num_platforms) *num_platforms = 0;
        return -1001;    /* CL_PLATFORM_NOT_FOUND_KHR: no driver */
    }
    return fn(num_entries, platforms, num_platforms);
}

typedef cl_int (*clGetPlatformInfo_fn)(cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetPlatformInfo(cl_platform_id platform, cl_platform_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetPlatformInfo_fn fn;
    if (!fn) fn = (clGetPlatformInfo_fn) resolve("clGetPlatformInfo");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(platform, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (*clGetDeviceIDs_fn)(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices, cl_uint * num_devices);
CL_API_ENTRY cl_int CL_API_CALL clGetDeviceIDs(cl_platform_id platform, cl_device_type device_type, cl_uint num_entries, cl_device_id * devices, cl_uint * num_devices) {
    static clGetDeviceIDs_fn fn;
    if (!fn) fn = (clGetDeviceIDs_fn) resolve("clGetDeviceIDs");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(platform, device_type, num_entries, devices, num_devices);
}

typedef cl_int (*clGetDeviceInfo_fn)(cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetDeviceInfo(cl_device_id device, cl_device_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetDeviceInfo_fn fn;
    if (!fn) fn = (clGetDeviceInfo_fn) resolve("clGetDeviceInfo");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_context (*clCreateContext_fn)(const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices, void (CL_CALLBACK * pfn_notify)(const char * errinfo, const void * private_info, size_t cb, void * user_data), void * user_data, cl_int * errcode_ret);
CL_API_ENTRY cl_context CL_API_CALL clCreateContext(const cl_context_properties * properties, cl_uint num_devices, const cl_device_id * devices, void (CL_CALLBACK * pfn_notify)(const char * errinfo, const void * private_info, size_t cb, void * user_data), void * user_data, cl_int * errcode_ret) {
    static clCreateContext_fn fn;
    if (!fn) fn = (clCreateContext_fn) resolve("clCreateContext");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(properties, num_devices, devices, pfn_notify, user_data, errcode_ret);
}

typedef cl_command_queue (*clCreateCommandQueue_fn)(cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret);
CL_API_ENTRY cl_command_queue CL_API_CALL clCreateCommandQueue(cl_context context, cl_device_id device, cl_command_queue_properties properties, cl_int * errcode_ret) {
    static clCreateCommandQueue_fn fn;
    if (!fn) fn = (clCreateCommandQueue_fn) resolve("clCreateCommandQueue");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, device, properties, errcode_ret);
}

typedef cl_mem (*clCreateBuffer_fn)(cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret);
CL_API_ENTRY cl_mem CL_API_CALL clCreateBuffer(cl_context context, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret) {
    static clCreateBuffer_fn fn;
    if (!fn) fn = (clCreateBuffer_fn) resolve("clCreateBuffer");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, flags, size, host_ptr, errcode_ret);
}

typedef cl_mem (*clCreateBufferWithProperties_fn)(cl_context context, const cl_mem_properties * properties, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret);
CL_API_ENTRY cl_mem CL_API_CALL clCreateBufferWithProperties(cl_context context, const cl_mem_properties * properties, cl_mem_flags flags, size_t size, void * host_ptr, cl_int * errcode_ret) {
    static clCreateBufferWithProperties_fn fn;
    if (!fn) fn = (clCreateBufferWithProperties_fn) resolve("clCreateBufferWithProperties");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, properties, flags, size, host_ptr, errcode_ret);
}

typedef cl_mem (*clCreateSubBuffer_fn)(cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type, const void * buffer_create_info, cl_int * errcode_ret);
CL_API_ENTRY cl_mem CL_API_CALL clCreateSubBuffer(cl_mem buffer, cl_mem_flags flags, cl_buffer_create_type buffer_create_type, const void * buffer_create_info, cl_int * errcode_ret) {
    static clCreateSubBuffer_fn fn;
    if (!fn) fn = (clCreateSubBuffer_fn) resolve("clCreateSubBuffer");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(buffer, flags, buffer_create_type, buffer_create_info, errcode_ret);
}

typedef cl_mem (*clCreateImage_fn)(cl_context context, cl_mem_flags flags, const cl_image_format * image_format, const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret);
CL_API_ENTRY cl_mem CL_API_CALL clCreateImage(cl_context context, cl_mem_flags flags, const cl_image_format * image_format, const cl_image_desc * image_desc, void * host_ptr, cl_int * errcode_ret) {
    static clCreateImage_fn fn;
    if (!fn) fn = (clCreateImage_fn) resolve("clCreateImage");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, flags, image_format, image_desc, host_ptr, errcode_ret);
}

typedef cl_int (*clReleaseMemObject_fn)(cl_mem memobj);
CL_API_ENTRY cl_int CL_API_CALL clReleaseMemObject(cl_mem memobj) {
    static clReleaseMemObject_fn fn;
    if (!fn) fn = (clReleaseMemObject_fn) resolve("clReleaseMemObject");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(memobj);
}

typedef cl_program (*clCreateProgramWithSource_fn)(cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret);
CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithSource(cl_context context, cl_uint count, const char ** strings, const size_t * lengths, cl_int * errcode_ret) {
    static clCreateProgramWithSource_fn fn;
    if (!fn) fn = (clCreateProgramWithSource_fn) resolve("clCreateProgramWithSource");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, count, strings, lengths, errcode_ret);
}

typedef cl_program (*clCreateProgramWithBinary_fn)(cl_context context, cl_uint num_devices, const cl_device_id * device_list, const size_t * lengths, const unsigned char ** binaries, cl_int * binary_status, cl_int * errcode_ret);
CL_API_ENTRY cl_program CL_API_CALL clCreateProgramWithBinary(cl_context context, cl_uint num_devices, const cl_device_id * device_list, const size_t * lengths, const unsigned char ** binaries, cl_int * binary_status, cl_int * errcode_ret) {
    static clCreateProgramWithBinary_fn fn;
    if (!fn) fn = (clCreateProgramWithBinary_fn) resolve("clCreateProgramWithBinary");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(context, num_devices, device_list, lengths, binaries, binary_status, errcode_ret);
}

typedef cl_int (*clBuildProgram_fn)(cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options, void (CL_CALLBACK * pfn_notify)(cl_program program, void * user_data), void * user_data);
CL_API_ENTRY cl_int CL_API_CALL clBuildProgram(cl_program program, cl_uint num_devices, const cl_device_id * device_list, const char * options, void (CL_CALLBACK * pfn_notify)(cl_program program, void * user_data), void * user_data) {
    static clBuildProgram_fn fn;
    if (!fn) fn = (clBuildProgram_fn) resolve("clBuildProgram");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(program, num_devices, device_list, options, pfn_notify, user_data);
}

typedef cl_int (*clGetProgramInfo_fn)(cl_program program, cl_program_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetProgramInfo(cl_program program, cl_program_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetProgramInfo_fn fn;
    if (!fn) fn = (clGetProgramInfo_fn) resolve("clGetProgramInfo");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(program, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (*clGetProgramBuildInfo_fn)(cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetProgramBuildInfo(cl_program program, cl_device_id device, cl_program_build_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetProgramBuildInfo_fn fn;
    if (!fn) fn = (clGetProgramBuildInfo_fn) resolve("clGetProgramBuildInfo");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(program, device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (*clReleaseProgram_fn)(cl_program program);
CL_API_ENTRY cl_int CL_API_CALL clReleaseProgram(cl_program program) {
    static clReleaseProgram_fn fn;
    if (!fn) fn = (clReleaseProgram_fn) resolve("clReleaseProgram");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(program);
}

typedef cl_kernel (*clCreateKernel_fn)(cl_program program, const char * kernel_name, cl_int * errcode_ret);
CL_API_ENTRY cl_kernel CL_API_CALL clCreateKernel(cl_program program, const char * kernel_name, cl_int * errcode_ret) {
    static clCreateKernel_fn fn;
    if (!fn) fn = (clCreateKernel_fn) resolve("clCreateKernel");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(program, kernel_name, errcode_ret);
}

typedef cl_int (*clSetKernelArg_fn)(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value);
CL_API_ENTRY cl_int CL_API_CALL clSetKernelArg(cl_kernel kernel, cl_uint arg_index, size_t arg_size, const void * arg_value) {
    static clSetKernelArg_fn fn;
    if (!fn) fn = (clSetKernelArg_fn) resolve("clSetKernelArg");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(kernel, arg_index, arg_size, arg_value);
}

typedef cl_int (*clGetKernelInfo_fn)(cl_kernel kernel, cl_kernel_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetKernelInfo(cl_kernel kernel, cl_kernel_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetKernelInfo_fn fn;
    if (!fn) fn = (clGetKernelInfo_fn) resolve("clGetKernelInfo");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(kernel, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (*clGetKernelWorkGroupInfo_fn)(cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetKernelWorkGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_work_group_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetKernelWorkGroupInfo_fn fn;
    if (!fn) fn = (clGetKernelWorkGroupInfo_fn) resolve("clGetKernelWorkGroupInfo");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(kernel, device, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (*clGetKernelSubGroupInfo_fn)(cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name, size_t input_value_size, const void* input_value, size_t param_value_size, void* param_value, size_t* param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetKernelSubGroupInfo(cl_kernel kernel, cl_device_id device, cl_kernel_sub_group_info param_name, size_t input_value_size, const void* input_value, size_t param_value_size, void* param_value, size_t* param_value_size_ret) {
    static clGetKernelSubGroupInfo_fn fn;
    if (!fn) fn = (clGetKernelSubGroupInfo_fn) resolve("clGetKernelSubGroupInfo");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(kernel, device, param_name, input_value_size, input_value, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (*clReleaseKernel_fn)(cl_kernel kernel);
CL_API_ENTRY cl_int CL_API_CALL clReleaseKernel(cl_kernel kernel) {
    static clReleaseKernel_fn fn;
    if (!fn) fn = (clReleaseKernel_fn) resolve("clReleaseKernel");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(kernel);
}

typedef cl_int (*clEnqueueNDRangeKernel_fn)(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset, const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueNDRangeKernel(cl_command_queue command_queue, cl_kernel kernel, cl_uint work_dim, const size_t * global_work_offset, const size_t * global_work_size, const size_t * local_work_size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueNDRangeKernel_fn fn;
    if (!fn) fn = (clEnqueueNDRangeKernel_fn) resolve("clEnqueueNDRangeKernel");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue, kernel, work_dim, global_work_offset, global_work_size, local_work_size, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (*clEnqueueReadBuffer_fn)(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueReadBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_read, size_t offset, size_t size, void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueReadBuffer_fn fn;
    if (!fn) fn = (clEnqueueReadBuffer_fn) resolve("clEnqueueReadBuffer");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue, buffer, blocking_read, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (*clEnqueueWriteBuffer_fn)(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size, const void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueWriteBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_write, size_t offset, size_t size, const void * ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueWriteBuffer_fn fn;
    if (!fn) fn = (clEnqueueWriteBuffer_fn) resolve("clEnqueueWriteBuffer");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue, buffer, blocking_write, offset, size, ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (*clEnqueueCopyBuffer_fn)(cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueCopyBuffer(cl_command_queue command_queue, cl_mem src_buffer, cl_mem dst_buffer, size_t src_offset, size_t dst_offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueCopyBuffer_fn fn;
    if (!fn) fn = (clEnqueueCopyBuffer_fn) resolve("clEnqueueCopyBuffer");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue, src_buffer, dst_buffer, src_offset, dst_offset, size, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (*clEnqueueFillBuffer_fn)(cl_command_queue command_queue, cl_mem buffer, const void * pattern, size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueFillBuffer(cl_command_queue command_queue, cl_mem buffer, const void * pattern, size_t pattern_size, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueFillBuffer_fn fn;
    if (!fn) fn = (clEnqueueFillBuffer_fn) resolve("clEnqueueFillBuffer");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue, buffer, pattern, pattern_size, offset, size, num_events_in_wait_list, event_wait_list, event);
}

typedef void * (*clEnqueueMapBuffer_fn)(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event, cl_int * errcode_ret);
CL_API_ENTRY void * CL_API_CALL clEnqueueMapBuffer(cl_command_queue command_queue, cl_mem buffer, cl_bool blocking_map, cl_map_flags map_flags, size_t offset, size_t size, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event, cl_int * errcode_ret) {
    static clEnqueueMapBuffer_fn fn;
    if (!fn) fn = (clEnqueueMapBuffer_fn) resolve("clEnqueueMapBuffer");
    if (!fn) {
        if (errcode_ret) *errcode_ret = CL_INVALID_OPERATION;
        return NULL;
    }
    return fn(command_queue, buffer, blocking_map, map_flags, offset, size, num_events_in_wait_list, event_wait_list, event, errcode_ret);
}

typedef cl_int (*clEnqueueUnmapMemObject_fn)(cl_command_queue command_queue, cl_mem memobj, void * mapped_ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueUnmapMemObject(cl_command_queue command_queue, cl_mem memobj, void * mapped_ptr, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueUnmapMemObject_fn fn;
    if (!fn) fn = (clEnqueueUnmapMemObject_fn) resolve("clEnqueueUnmapMemObject");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue, memobj, mapped_ptr, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (*clEnqueueMarkerWithWaitList_fn)(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueMarkerWithWaitList(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueMarkerWithWaitList_fn fn;
    if (!fn) fn = (clEnqueueMarkerWithWaitList_fn) resolve("clEnqueueMarkerWithWaitList");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (*clEnqueueBarrierWithWaitList_fn)(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event);
CL_API_ENTRY cl_int CL_API_CALL clEnqueueBarrierWithWaitList(cl_command_queue command_queue, cl_uint num_events_in_wait_list, const cl_event * event_wait_list, cl_event * event) {
    static clEnqueueBarrierWithWaitList_fn fn;
    if (!fn) fn = (clEnqueueBarrierWithWaitList_fn) resolve("clEnqueueBarrierWithWaitList");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue, num_events_in_wait_list, event_wait_list, event);
}

typedef cl_int (*clWaitForEvents_fn)(cl_uint num_events, const cl_event * event_list);
CL_API_ENTRY cl_int CL_API_CALL clWaitForEvents(cl_uint num_events, const cl_event * event_list) {
    static clWaitForEvents_fn fn;
    if (!fn) fn = (clWaitForEvents_fn) resolve("clWaitForEvents");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(num_events, event_list);
}

typedef cl_int (*clGetEventProfilingInfo_fn)(cl_event event, cl_profiling_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret);
CL_API_ENTRY cl_int CL_API_CALL clGetEventProfilingInfo(cl_event event, cl_profiling_info param_name, size_t param_value_size, void * param_value, size_t * param_value_size_ret) {
    static clGetEventProfilingInfo_fn fn;
    if (!fn) fn = (clGetEventProfilingInfo_fn) resolve("clGetEventProfilingInfo");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(event, param_name, param_value_size, param_value, param_value_size_ret);
}

typedef cl_int (*clReleaseEvent_fn)(cl_event event);
CL_API_ENTRY cl_int CL_API_CALL clReleaseEvent(cl_event event) {
    static clReleaseEvent_fn fn;
    if (!fn) fn = (clReleaseEvent_fn) resolve("clReleaseEvent");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(event);
}

typedef cl_int (*clFlush_fn)(cl_command_queue command_queue);
CL_API_ENTRY cl_int CL_API_CALL clFlush(cl_command_queue command_queue) {
    static clFlush_fn fn;
    if (!fn) fn = (clFlush_fn) resolve("clFlush");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue);
}

typedef cl_int (*clFinish_fn)(cl_command_queue command_queue);
CL_API_ENTRY cl_int CL_API_CALL clFinish(cl_command_queue command_queue) {
    static clFinish_fn fn;
    if (!fn) fn = (clFinish_fn) resolve("clFinish");
    if (!fn) {
        return CL_INVALID_OPERATION;
    }
    return fn(command_queue);
}

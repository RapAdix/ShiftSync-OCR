LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := line_detector_native
LOCAL_SRC_FILES := line_detector_native.cpp
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)

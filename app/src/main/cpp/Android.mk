LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)
LOCAL_MODULE := handtop
LOCAL_SRC_FILES := handtop.c
LOCAL_CFLAGS := -std=c11 -Wall -Wextra -O2
LOCAL_LDLIBS := -ljnigraphics
include $(BUILD_SHARED_LIBRARY)

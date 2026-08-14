#include "gl41metal_internal.h"

using namespace gl41metal;

namespace gl41metal {

void resetSubmittedSlot(NativeContext* context, int slotIndex) {
  std::lock_guard<std::mutex> lock(context->mutex);
  Slot& slot = context->slots[slotIndex];
  if (slot.state == SlotState::MetalSubmitted) {
    slot.state = SlotState::Free;
    slot.frameId = -1;
  }
  context->pendingCommandBuffers = std::max(0, context->pendingCommandBuffers - 1);
  context->condition.notify_all();
}

}  // namespace gl41metal

extern "C" {

JNIEXPORT jint JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_Gl41MetalNative_acquireFreeSlot(
    JNIEnv* env,
    jclass,
    jlong handle) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr) {
    return -1;
  }
  std::lock_guard<std::mutex> lock(context->mutex);
  for (size_t i = 0; i < context->slots.size(); i++) {
    Slot& slot = context->slots[i];
    if (slot.state == SlotState::Free) {
      return static_cast<jint>(i);
    }
  }
  return -1;
}

JNIEXPORT jint JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_Gl41MetalNative_waitCurrent(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint currentSlot,
    jint timeoutMs) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr) {
    return -1;
  }
  std::unique_lock<std::mutex> lock(context->mutex);
  if (currentSlot < 0 || currentSlot >= static_cast<jint>(context->slots.size())) {
    return -1;
  }
  auto readyOrFailed = [&] {
    return context->slots[currentSlot].state == SlotState::MetalReady ||
        !context->asyncFailure.empty();
  };
  if (timeoutMs <= 0) {
    context->condition.wait(lock, readyOrFailed);
  } else {
    context->condition.wait_for(lock, std::chrono::milliseconds(timeoutMs), readyOrFailed);
  }
  if (!context->asyncFailure.empty()) {
    throwJava(env, context->asyncFailure);
    return -1;
  }
  int chosen = -1;
  if (context->slots[currentSlot].state == SlotState::MetalReady) {
    chosen = currentSlot;
  } else {
    // Bounded wait expired before the current frame's Metal work finished. Composite the
    // newest completed frame instead of stalling the render thread (heavy ingest churn on
    // servers produced 20-50ms Metal frames) or dropping the distant layer for a frame.
    int64_t bestFrame = -1;
    for (size_t i = 0; i < context->slots.size(); i++) {
      Slot& s = context->slots[i];
      if (s.state == SlotState::MetalReady && s.frameId > bestFrame) {
        bestFrame = s.frameId;
        chosen = static_cast<int>(i);
      }
    }
  }
  if (chosen < 0) {
    return -1;
  }
  // Older completed frames superseded by `chosen` will never be sampled; free them so the
  // slot pool cannot starve while the stale-sampling fallback is active.
  for (size_t i = 0; i < context->slots.size(); i++) {
    Slot& s = context->slots[i];
    if (static_cast<int>(i) != chosen && s.state == SlotState::MetalReady &&
        s.frameId < context->slots[chosen].frameId) {
      s.state = SlotState::Free;
      s.frameId = -1;
    }
  }
  context->slots[chosen].state = SlotState::GlSampling;
  return chosen;
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_Gl41MetalNative_discardCurrentSlot(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint slotIndex) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr) {
    return;
  }
  if (slotIndex < 0 || slotIndex >= static_cast<jint>(context->slots.size())) {
    throwJava(env, "GL41Metal discard received an invalid slot index");
    return;
  }
  {
    std::lock_guard<std::mutex> lock(context->mutex);
    Slot& slot = context->slots[slotIndex];
    if (slot.state == SlotState::MetalReady) {
      slot.state = SlotState::Free;
      slot.frameId = -1;
    } else if (slot.state == SlotState::MetalSubmitted) {
      slot.state = SlotState::Retiring;
    }
  }
  context->condition.notify_all();
}

JNIEXPORT void JNICALL
Java_me_cortex_voxy_client_core_rendering_backend_gl41metal_Gl41MetalNative_releaseSampledSlot(
    JNIEnv* env,
    jclass,
    jlong handle,
    jint slotIndex) {
  NativeContext* context = requireContext(env, handle);
  if (context == nullptr) {
    return;
  }
  if (slotIndex < 0 || slotIndex >= static_cast<jint>(context->slots.size())) {
    throwJava(env, "GL41Metal release received an invalid slot index");
    return;
  }
  {
    std::lock_guard<std::mutex> lock(context->mutex);
    Slot& slot = context->slots[slotIndex];
    if (slot.state == SlotState::GlSampling) {
      slot.state = SlotState::Retiring;
      slot.state = SlotState::Free;
      slot.frameId = -1;
    }
  }
  context->condition.notify_all();
}

}  // extern "C"

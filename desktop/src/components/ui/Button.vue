<script setup lang="ts">
import { Primitive } from "reka-ui";
import { cn } from "./cn";
const props = withDefaults(defineProps<{
  variant?: "primary" | "secondary" | "ghost" | "outline" | "icon" | "danger";
  size?: "default" | "compact";
  type?: "button" | "submit"; as?: string; disabled?: boolean; busy?: boolean; class?: unknown;
}>(), { variant:"secondary", size:"default", type:"button", as:"button", busy:false });
function guard(event:Event) {
  if(props.busy || props.disabled) { event.preventDefault(); event.stopImmediatePropagation(); }
}
</script>
<template>
  <Primitive :as="as" :type="as === 'button' ? type : undefined"
    :disabled="as === 'button' ? disabled || busy : undefined" :aria-disabled="disabled || busy || undefined"
    :aria-busy="busy || undefined" :class="cn('ic-action', variant, size, props.class)" @click.capture="guard"><slot /></Primitive>
</template>

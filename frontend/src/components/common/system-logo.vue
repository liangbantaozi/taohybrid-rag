<script lang="ts" setup>
defineOptions({ name: 'SystemLogo' });
</script>

<template>
  <span class="coldy-logo" role="img" aria-label="Coldy" />
</template>

<style scoped>
.coldy-logo {
  display: inline-block;
  width: 1em;
  height: 1em;
  background-image: url('/coldy-spritesheet.png');
  background-position: 0 0;
  background-repeat: no-repeat;
  background-size: 800% 900%;
  image-rendering: pixelated;
}
</style>

<template><RouterView :key="$route.meta.public ? $route.path : 'application'" /></template>

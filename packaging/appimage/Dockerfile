# Debian 12 keeps the bundled client compatible with glibc 2.36 and newer.
FROM docker.io/library/rust:1.88-slim-bookworm@sha256:38bc5a86d998772d4aec2348656ed21438d20fcdce2795b56ca434cf21430d89

RUN apt-get update -qq && apt-get install -y --no-install-recommends \
    adb build-essential ca-certificates clang curl desktop-file-utils \
    libclang-dev libdbus-1-dev meson ninja-build pkg-config python3 \
    && rm -rf /var/lib/apt/lists/*

# Build a portable PipeWire client and its plugins; package them without
# replacing the user's audio server. Pin and verify the sources.
RUN curl -fsSL --retry 2 https://codeload.github.com/PipeWire/pipewire/tar.gz/91a1ce183fe8a77843ebe72d62aae51e5ce62737 -o /tmp/pipewire.tar.gz \
    && echo '11c3c3a7f8264de31727dbf3dc6b9e1a517390b74a0f2a668b02c314f7527c7c  /tmp/pipewire.tar.gz' | sha256sum -c - \
    && mkdir /tmp/pipewire \
    && tar -xf /tmp/pipewire.tar.gz -C /tmp/pipewire --strip-components=1 \
    && meson setup /tmp/pipewire/build /tmp/pipewire --prefix=/opt/pipewire --libdir=lib \
       --buildtype=release -Dauto_features=disabled -Dsession-managers=[] \
       -Dexamples=disabled -Dtests=disabled -Dpipewire-jack=disabled \
       -Dpipewire-v4l2=disabled -Ddbus=enabled -Dsupport=enabled \
       -Daudioconvert=enabled -Daudiomixer=enabled -Dvideoconvert=disabled \
       -Dvideotestsrc=disabled -Daudiotestsrc=disabled -Dcontrol=disabled \
       -Dflatpak=disabled \
    && ninja -C /tmp/pipewire/build -j 4 \
    && ninja -C /tmp/pipewire/build install \
    && mkdir -p /opt/pipewire/licenses \
    && cp /tmp/pipewire/COPYING /tmp/pipewire/LICENSE /opt/pipewire/licenses/ \
    && rm -rf /tmp/pipewire /tmp/pipewire.tar.gz

RUN curl -fsSL --retry 2 https://github.com/AppImage/appimagetool/releases/download/1.9.1/appimagetool-x86_64.AppImage -o /opt/appimagetool.AppImage \
    && echo 'ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0  /opt/appimagetool.AppImage' | sha256sum -c - \
    && chmod +x /opt/appimagetool.AppImage \
    && curl -fsSL --retry 2 https://github.com/AppImage/type2-runtime/releases/download/continuous/runtime-x86_64 -o /opt/appimage-runtime \
    && echo '156f4bdbde9c52d01814600013e0a273f0118dc2de98975f3c8c63427ec79074  /opt/appimage-runtime' | sha256sum -c -

RUN apt-get update -qq && apt-get install -y --no-install-recommends file \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL --retry 2 https://raw.githubusercontent.com/AppImage/type2-runtime/8f39b89e2ac31e1640b3d3f7e9a5108e6ce805fa/LICENSE -o /opt/appimage-runtime.LICENSE

ENV PKG_CONFIG_PATH=/opt/pipewire/lib/pkgconfig
ENV LD_LIBRARY_PATH=/opt/pipewire/lib
WORKDIR /build

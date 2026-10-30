# 同步库的 Android 构建（可复现）

`agent_os/sync` 需要交叉编译成 `libtraceform_sync.so` 才能被 Android 加载。本文件记录可复现的构建方式与已验证的对齐要求。

## 工具链

- Rust：rustup 的 stable 工具链，已安装 `aarch64-linux-android` 目标（本机 1.99.1）。
- NDK：26.3.11579264，API 29 的 clang（`aarch64-linux-android29-clang`）。
- 依赖由 `agent_os/sync/Cargo.lock` 锁定。

## 命令

```sh
cd agent_os/sync
NDK=$HOME/Library/Android/sdk/ndk/26.3.11579264
TOOLCHAIN=$NDK/toolchains/llvm/prebuilt/darwin-x86_64
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER=$TOOLCHAIN/bin/aarch64-linux-android29-clang
export CC_aarch64_linux_android=$TOOLCHAIN/bin/aarch64-linux-android29-clang
export AR_aarch64_linux_android=$TOOLCHAIN/bin/llvm-ar
TC=$HOME/.rustup/toolchains/stable-aarch64-apple-darwin
export RUSTC=$TC/bin/rustc
CARGO_HOME=../.cargo-home $TC/bin/cargo build --release --target aarch64-linux-android --lib
```

产物：`target/aarch64-linux-android/release/libtraceform_sync.so`。

## 16 KB 页对齐（必须）

Android 15 起部分设备使用 16 KB 页；未对齐的 LOAD 段会触发系统警告并可能无法加载。`agent_os/sync/.cargo/config.toml` 固定了链接参数：

```toml
[target.aarch64-linux-android]
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384"]
```

验证：

```sh
$TOOLCHAIN/bin/llvm-readelf -lW target/aarch64-linux-android/release/libtraceform_sync.so | grep LOAD
```

四个 LOAD 段的 Align 必须都是 `0x4000`。

## 已验证

- 2026-10-31：加入上述 rustflags 后重建，四个 LOAD 段 Align 均为 `0x4000`（修复前为 `0x1000`，隔离包启动时系统报“不符合 16 KB 对齐要求”）。
- 2026-10-31 在设备上复测：用对齐后的库重建隔离包并安装，启动时不再出现 16 KB 对齐警告。

## 注意

- `target/` 与 `.cargo-home/` 均被忽略，不进入仓库；构建产物需要在发布流程里单独校验摘要。
- 该库目前只用于隔离验证包，未进入正式 APK；正式接入前仍需决定打包方式与签名后的校验。

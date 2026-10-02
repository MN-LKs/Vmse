# Vmse —— 基于 Simple Voice Chat 的立体声音乐插件

**Vmse**（前身 DSMPMusic）是一款面向 Minecraft 1.21.11 服务器的音乐播放插件，依托 Simple Voice Chat 的 `LocationalAudioChannel` 实现真立体声 / 5.1 环绕声播放，支持动态声源跟随、空间声学反射、歌单连播、音乐区域、金锄头声源工具、音效预设、歌词同步等功能。

## 功能特性

- 🔊 **立体声 / 5.1 环绕** — 基于 SVoiceChat 空间音频，声源随玩家朝向旋转
- 🗺️ **音乐区域** — 圆形/方形区域，进入自动播放、离开自动停止
- 📋 **歌单系统** — 顺序/随机/循环/单曲循环，YAML 持久化
- 🎵 **音效预设** — 蝰蛇 / 杜比 / 3D 环绕 / 低音炮 / 纯净人声，实时 DSP
- 🔧 **金锄头工具** — 手持金锄头右键放置声源
- 📝 **歌词同步** — mc_lyrics v1 JSON，逐字高亮，actionbar/title/bossbar/chat
- 🔄 **数据迁移** — 启动自动迁移旧 DSMPMusic 数据，热加载安全

## 依赖

- Paper API 1.21.11
- Simple Voice Chat API 2.6.20
- Voice Messages（可选，`WWLeFuHa:mBsQhubX`）
- JLayer / MP3SPI / VorbisSPI

## 安装

1. 将 `Vmse-1.0.0.jar` 放入 `plugins/`
2. 重启服务器，首次启动自动生成配置
3. 用 `/vmse help` 查看指令

## 指令速查

| 命令 | 用途 |
|---|---|
| `/vmse play <目标> <文件> [-original]` | 播放 |
| `/vmse stop/pause/list/reload` | 停止/暂停/列表/重载 |
| `/vmse surround [玩家] [stereo\|5.1\|mono]` | 立体声阵型 |
| `/vmse region create/remove/list/info` | 音乐区域 |
| `/vmse effect list/set/info` | 音效预设 |
| `/vmse hoe` | 金锄头 |
| `/vmse confirm source/bandwidth` | 确认操作 |

## 构建

```bash
cd vmse
mvn -q -o clean package -DskipTests
# 输出: target/Vmse.jar → 复制到 plugins/
```

## 版本

- 1.0.0 — 独立插件化，音效系统重做，区域歌单连播修复

## 许可证

MIT

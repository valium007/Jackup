# Jackup

A lightweight [Paper](https://papermc.io/) plugin that automatically creates compressed, timestamped backups of your Minecraft worlds on a schedule — and prunes old ones so they don't pile up.

Backups are written as **Zstandard-compressed tar archives** (`.tar.zst`), which give significantly smaller files than gzip at comparable speed.

## Features

- ⏱️ **Scheduled backups** — runs on a configurable interval, fully asynchronously so it won't freeze the main server thread.
- 🗜️ **Zstd compression** — `.tar.zst` archives with a configurable compression level (default 10).
- 🧹 **Automatic cleanup** — keeps only the newest *N* backups; older archives are deleted automatically.
- 🌍 **Multi-world** — back up any number of world directories.
- 🔒 **Safe archiving** — skips Minecraft's `session.lock` and handles long file paths.

## Requirements

- **Minecraft / Paper:** 26.2
- **Java:** 25 (required by the Paper 26.x line)

## Installation

1. Download or build `Jackup-<version>.jar` (see [Building](#building)).
2. Drop the jar into your server's `plugins/` folder.
3. Start the server once to generate the default `plugins/Jackup/config.yml`.
4. Edit the config to taste, then restart (or reload) the server.

## Configuration

The default `config.yml`:

```yaml
# How often to run a backup, in seconds. (6 hours)
backup-frequency: 21600

# Delay before the first backup after the server starts, in seconds. (5 minutes)
initial-delay: 300

# Maximum number of backups to keep. Oldest are deleted once this is exceeded.
max-backups: 4

# Zstandard compression level (1 = fastest, 22 = smallest). 10 is a good balance.
compression-level: 10

# Folder where backup archives are written (relative to the server directory).
backup-path: backups

# Worlds to back up. These are directory names relative to the server directory.
# On modern Minecraft all dimensions live inside the main world folder, so
# listing "world" backs up the nether and end too.
worlds:
  - world
```

| Key                | Type     | Default                              | Description                                                                                  |
| ------------------ | -------- | ------------------------------------ | -------------------------------------------------------------------------------------------- |
| `backup-frequency` | int      | `21600`                              | Interval between backups, in seconds.                                                          |
| `initial-delay`    | int      | `300`                                | Seconds to wait after startup before the first backup, so it isn't taken mid-generation.      |
| `max-backups`      | int      | `4`                                  | How many `.tar.zst` archives to keep. The oldest are pruned once this is exceeded.            |
| `compression-level`| int      | `10`                                 | Zstd level, `1` (fastest) to `22` (smallest).                                                 |
| `backup-path`      | string   | `backups`                            | Output folder. Relative to the **server directory**; an absolute path also works.            |
| `worlds`           | list     | `world`                              | World directory names to back up, relative to the server directory.                           |

## How it works

On enable, Jackup loads the config, ensures the backup directory exists, and schedules a recurring task that first runs after `initial-delay`. Each run:

1. On the main thread, **flushes every loaded world to disk and pauses auto-save**, so the snapshot is consistent.
2. Hands off to an async task that streams each configured world directory into `<backup-path>/<world>_<timestamp>.tar.zst` (timestamp format `yyyy-MM-dd_HH-mm-ss`), skipping `session.lock` and non-regular files. Because modern Minecraft stores the nether and end inside the world folder (`dimensions/`), backing up `world` captures all dimensions.
3. Deletes the oldest `.tar.zst` files until at most `max-backups` remain.
4. Re-enables auto-save on the main thread.

> **Note:** Archiving still happens while the server is live, but flushing + pausing auto-save first means it reads a stable on-disk copy rather than half-written files.

## Building

This project uses Gradle with the [Shadow](https://gradleup.com/shadow/) plugin to produce a self-contained jar (bundling the Kotlin stdlib, Apache Commons Compress, and zstd-jni).

```bash
./gradlew build
```

The output jar lands in `build/libs/`.

### Run a test server

The [run-paper](https://github.com/jpenilla/run-paper) plugin can launch a real Paper 26.2 server with the plugin installed:

```bash
./gradlew runServer
```

## Tech stack

- **Language:** Kotlin 2.4.0 (JVM toolchain 25)
- **Platform:** Paper API 26.2
- **Compression:** Apache Commons Compress + zstd-jni

## License

No license specified yet.

# Janice: Sync Files.

## What is Janice

Janice is a high-performance file sync tool built to provide superior performance to rsync in large-scale directories with hundreds of thousands of files.

Janice leverages Kotlin Coroutines alongside Java NIO's asynchronous file and networking capabilities to maximize overall system performance.

## How to use

Check [config.json.jsonc](./doc/config.json.jsonc) for how to configure workspaces.

Check [commandline.md](./doc/commandline.md) for how to launch janice.

If you are using packaged Janice from releases that comes with "run-janice.bat" or "run-janice.sh" script and already included Tencent Kona JDK, just call run-janice and pass command line arguments to it.

Like:

```bash
./run-janice.sh --server --config ./config.json
```

If you want to run Janice on your own JDK, please make sure your JDK version is at least 25.

## How to build

If you want to package Janice yourself, you can do it by simply run `python3 scripts/package.py --version {x}` , in which `{x}` is the current version tag defined in `build.gradle.kts`.

For example, if you see following in `build.gradle.kts`

```kotlin
val versionMajor = 0
val versionMinor = 0
val versionPatch = 1
val versionTail = "-alpha-evaluation"  // like: -dev
```

Then your version should be `0.0.1-alpha-evaluation`. So you have to run `python3 scripts/package.py --version 0.0.1-alpha-evaluation` to package Janice yourself.

## Janice Protocol

Janice Protocol is the way Janice processes communicates.

Janice Protocol is specified under folder `protocol`

You can access protocol version n by reading `protocol/{n}.md`, in which `{n}` is the version code like 1.

## Janice Releases

| Family | First Release Date     | Latest | Latest Release Date    | Protocol Version(s) |
| ------ | ---------------------- | ------ | ---------------------- | ------------------- |
| 1.0    | 2026-01-14T16:28+08:00 | 1.0.1  | 2026-01-14T18:36+08:00 | 1                   |

## Requirements

Janice requires a Java 25 environment.

It is recommended that you use [Tencent Kona 25](https://github.com/Tencent/TencentKona-25) for running Janice.

But maybe you can simply download from releases, which have Tencent Kona JDK and "run-janice" script included.

## Benchmarks

### Workspace with 350000 files, 13GB

Hardware & Software

|                  |                             Client                             |                             Server                             |
| :--------------: | :------------------------------------------------------------: | :------------------------------------------------------------: |
|   **OS**   |                   Windows 11 23H2 22631.6199                   |                GNU/Linux 6.6.98-40.2.tl4.x86_64                |
|  **CPU**  |                            i9-9900                            |                           EPYC 9754                           |
| **Memory** |                              DDR4                              |                                                                |
| **Janice** | commit 295b3dfb8f316d081458d63691c37461ca3de924 protocol draft | commit 295b3dfb8f316d081458d63691c37461ca3de924 protocol draft |
| **rsync** |               version 3.1.2 protocol version 31               |               version 3.2.7 protocol version 31               |

Statistics

|                  |  Full Sync  |  No change  | 1 file changed |
| :--------------: | :---------: | :---------: | :------------: |
|      Janice     | 201 seconds |  3 seconds  |  2.7 seconds  |
|      rsync      | 382 seconds | 126 seconds |  119 seconds  |
| Performance gain |   +90.04%   |   +4100%   |     +4307%     |

### Workspace with 60766 files, 12GB

Hardware & Software

|                  |                             Client                             |                             Server                             |
| :--------------: | :------------------------------------------------------------: | :------------------------------------------------------------: |
|   **OS**   |                   Windows 11 23H2 22631.6199                   |                GNU/Linux 6.6.98-40.2.tl4.x86_64                |
|  **CPU**  |                            i9-9900                            |                           EPYC 9754                           |
| **Memory** |                              DDR4                              |                                                                |
| **Janice** | commit 4da15bcd450e9b8b8dd1a6a5e1cc7a3c9d0589a6 protocol draft | commit 4da15bcd450e9b8b8dd1a6a5e1cc7a3c9d0589a6 protocol draft |
| **rsync** |               version 3.1.2 protocol version 31               |               version 3.2.7 protocol version 31               |

Statistics

|                  |  Full Sync  |  No change  | 1 file changed |
| :--------------: | :---------: | :---------: | :------------: |
|      Janice      | 157 seconds | 2.3 seconds |  2.3 seconds  |
|      rsync      | 259 seconds | 66 seconds |   66 seconds   |
| Performance gain |    +64%    |   +2769%   |     +2769%     |

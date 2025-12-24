# Janus: Sync Files.

## What is Janus

Janus is a high-performance file sync tool built to provide superior performance to rsync in large-scale directories with hundreds of thousands of files.

Janus leverages Kotlin Coroutines alongside Java NIO's asynchronous file and networking capabilities to maximize overall system performance.

## Janus Protocol

Janus Protocol is the way Janus processes communicates.

Janus Protocol is specified under folder `protocol`

You can access protocol version n by reading `protocol/{n}.md`, in which `{n}` is the version code like 1.

## Janus Releases

| Family | First Release Date | Latest | Latest Release Date | Protocol Version(s) |
| ------ | ------------------ | ------ | ------------------- | ------------------- |
| 1.0    | TBD                | 1.0.0  | TBD                 | 1                   |

## Requirements

Janus requires a Java 25 environment.

It is recommended that you use [Tencent Kona 25](https://github.com/Tencent/TencentKona-25) for running Janus.

## Benchmarks

### Workspace with 350000 files, 13GB

Hardware & Software

|                  |                             Client                             |                             Server                             |
| :--------------: | :------------------------------------------------------------: | :------------------------------------------------------------: |
|   **OS**   |                   Windows 11 23H2 22631.6199                   |                GNU/Linux 6.6.98-40.2.tl4.x86_64                |
|  **CPU**  |                            i9-9900                            |                           EPYC 9754                           |
| **Memory** |                              DDR4                              |                                                                |
| **Janus** | commit bf594c7f95a2fc46c5e128a39397d8b3e30e827f protocol draft | commit bf594c7f95a2fc46c5e128a39397d8b3e30e827f protocol draft |
| **rsync** |               version 3.1.2 protocol version 31               |               version 3.2.7 protocol version 31               |

Statistics

|                  |  Full Sync  |  No change  | 1 file changed |
| :--------------: | :---------: | :---------: | :------------: |
|      Janus      | 291 seconds |  3 seconds  |  2.7 seconds  |
|      rsync      | 382 seconds | 126 seconds |  119 seconds  |
| Performance gain |   +31.2%   |   +4100%   |     +4307%     |

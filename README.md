# Janus: Sync Files.

## What is Janus

Janus is a high-performance file sync tool built to provide superior performance to rsync in large-scale directories with hundreds of thousands of files.

Janus leverages Kotlin Coroutines alongside Java NIO's asynchronous file and networking capabilities to maximize overall system performance.

## Janus Protocol

Janus Protocol is the way Janus processes communicates.

Janus Protocol is specified under folder `protocol`

You can access protocol version n by reading `protocol/{n}.md`, in which `{n}` is the version code like 1.

## Janus Releases

TODO

| Family | First Release Date | Latest | Latest Release Date | Protocol Version(s) |
| ------ | ------------------ | ------ | ------------------- | ------------------- |
| 1.0    |                    | 1.0.0  |                     | 1                   |

## Requirements

Janus requires a Java 25 environment.

It is recommended that you use [Tencent Kona 25](https://github.com/Tencent/TencentKona-25) for running Janus.

## Benchmarks

TODO

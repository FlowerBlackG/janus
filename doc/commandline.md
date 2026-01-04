# Janus Commandline

## Common

### --server / --client

Specify run mode. Have higher priority than config.json.

If not found in both commandline and config.json, Janus will quit.

### --ip [value] / --host [value]

Must have for running in client mode.

Value is a string, like: 192.168.1.3

### --port [value]

Must have for running in client mode.

### --config [file]

Config in json.

See config.json.jsonc for usage.

### --workspace [value]

Select which workspace to sync. Workspaces should be configured in config.json.jsonc, or configured temporarily by commandline.

### --path [value]

Optional. Specify a workspace path to use.

Merges with config.json.

Configuring workspaces in config.json is highly recommended. This cli is for temporary use.

### --secret [value]

AES key, paired to --path. If not set, connection to server will not be authenticated. If server requires authentication, Janus will quit.

Type: plain text.

Highly recommended to set. But you'd better set it in config.json.

### --ssl-cert [path]

Path to ssl cert. Optional, but highly recommended.

### --ssl-key [path]

Path to ssl key. Optional, but highly recommended.

---

## Janus Server

### --port [value]

Specify a port to listen. If not set, port is selected automatically.

---

## Janus Client

### --dangling [value]

Optional. If set, will be the default way to solve dangling files.

"remove" or "keep" or "panic".

---

## Janus Subprograms

### --version

Show Janus version and exit.

### --help / --usage

Show usage and exit.

### --generate-ssl-keys

Generate ECP384 SSL keys. If --ssl-cert and --ssl-key set, will save to the paths.

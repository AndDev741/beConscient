#!/bin/sh
# Installs the beConscient desktop agent for the current user:
#   - the binary in ~/.local/bin (or $BINDIR)
#   - the systemd --user unit
#   - the native messaging host manifest for Chrome, Brave Nightly and Brave
#   - the state and config directories, mode 0700
#
# Usage: packaging/install.sh [--binary PATH] [--enable] [--uninstall]
#   --binary PATH  install this binary instead of building one with `go build`
#   --enable       also enable and start the unit (systemctl --user enable --now)
#   --uninstall    remove the binary, unit and manifests (keeps queue and config)
#
# Set BECONSCIENT_NO_SYSTEMCTL=1 to skip every systemctl call (for testing in
# a scratch HOME).
set -eu

umask 077

here=$(cd "$(dirname "$0")" && pwd)
desktop=$(dirname "$here")

binary_src=""
enable=0
uninstall=0
while [ $# -gt 0 ]; do
	case "$1" in
	--binary) binary_src=$2; shift 2 ;;
	--enable) enable=1; shift ;;
	--uninstall) uninstall=1; shift ;;
	-h|--help) sed -n '2,14p' "$0"; exit 0 ;;
	*) echo "install.sh: unknown argument $1" >&2; exit 2 ;;
	esac
done

: "${HOME:?HOME is not set}"
bindir=${BINDIR:-$HOME/.local/bin}
config_home=${XDG_CONFIG_HOME:-$HOME/.config}
state_home=${XDG_STATE_HOME:-$HOME/.local/state}
unit_dir=$config_home/systemd/user
unit=$unit_dir/beconscient-agent.service
binary=$bindir/beconscient-agent
manifest_name=org.beconscient.agent.json
browser_dirs="
$config_home/google-chrome/NativeMessagingHosts
$config_home/BraveSoftware/Brave-Browser-Nightly/NativeMessagingHosts
$config_home/BraveSoftware/Brave-Browser/NativeMessagingHosts
"

systemctl_user() {
	if [ "${BECONSCIENT_NO_SYSTEMCTL:-0}" = 1 ]; then
		echo "(skipped) systemctl --user $*"
	else
		systemctl --user "$@"
	fi
}

if [ "$uninstall" = 1 ]; then
	systemctl_user disable --now beconscient-agent.service 2>/dev/null || true
	rm -f "$unit" "$binary"
	for d in $browser_dirs; do rm -f "$d/$manifest_name"; done
	systemctl_user daemon-reload || true
	echo "Removed the binary, unit and manifests. The queue ($state_home/beconscient) and config ($config_home/beconscient) are kept."
	exit 0
fi

# The binary path goes into JSON and a unit file: keep it plain.
case "$binary" in
*'"'*|*'\'*|*'
'*|*' '*) echo "install.sh: unsupported characters in $binary" >&2; exit 1 ;;
esac
case "$binary" in
/*) ;;
*) echo "install.sh: $binary is not absolute" >&2; exit 1 ;;
esac

# 1. Binary.
mkdir -p "$bindir"
if [ -z "$binary_src" ]; then
	tmp=$(mktemp)
	trap 'rm -f "$tmp"' EXIT
	(cd "$desktop" && go build -trimpath -o "$tmp" ./cmd/beconscient-agent)
	binary_src=$tmp
fi
install -m 0755 "$binary_src" "$binary.new"
mv -f "$binary.new" "$binary"
echo "Installed $binary"

# 2. State and config directories (the runtime socket directory is created
#    by the agent in $XDG_RUNTIME_DIR at every start).
for d in "$state_home/beconscient" "$config_home/beconscient"; do
	mkdir -p "$d"
	chmod 0700 "$d"
done
echo "Prepared $state_home/beconscient and $config_home/beconscient (0700)"

# 3. systemd --user unit.
mkdir -p "$unit_dir"
sed "s|@BINARY@|$binary|g" "$here/beconscient-agent.service" > "$unit.new"
chmod 0644 "$unit.new"
mv -f "$unit.new" "$unit"
echo "Wrote $unit"

# 4. Native messaging host manifests.
for d in $browser_dirs; do
	mkdir -p "$d"
	sed "s|@BINARY@|$binary|g" "$here/native-host/$manifest_name" > "$d/$manifest_name.new"
	chmod 0644 "$d/$manifest_name.new"
	mv -f "$d/$manifest_name.new" "$d/$manifest_name"
	echo "Wrote $d/$manifest_name"
done

systemctl_user daemon-reload
if [ "$enable" = 1 ]; then
	systemctl_user enable --now beconscient-agent.service
	echo "Enabled and started beconscient-agent.service"
else
	echo
	echo "Next steps:"
	echo "  systemctl --user enable --now beconscient-agent.service"
	echo "  $binary pair --server https://<your server> --key <server public key json>   (code on stdin)"
	echo "  $binary status"
fi

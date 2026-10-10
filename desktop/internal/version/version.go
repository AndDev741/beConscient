// Package version holds the client version reported in batches and health
// records. Release builds override it with
// -ldflags "-X github.com/AndDev741/beConscient/desktop/internal/version.Version=x.y.z".
package version

// Version is the agent's client version (max 32 characters on the wire).
var Version = "0.1.0"

// ContractVersion is the version of contract/envelope.md and events.md.
const ContractVersion = 1

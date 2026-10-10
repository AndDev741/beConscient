// Command beconscient-agent is the beConscient desktop agent for GNOME on
// Wayland. It measures active screen time without reading window titles,
// application names or URLs, queues it in SQLite and uploads it inside HPKE
// envelopes. Started by the browser with an extension origin as its first
// argument, it runs as the native messaging host instead.
//
//	beconscient-agent [run] [--dev]
//	beconscient-agent pair --server <https url> --key <public key json> [--name <name>] [--dev]
//	beconscient-agent status
//	beconscient-agent replay --since <YYYY-MM-DD | RFC 3339>
//	beconscient-agent probe [--seconds 30]
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"golang.org/x/sys/unix"

	"github.com/AndDev741/beConscient/desktop/internal/activity"
	"github.com/AndDev741/beConscient/desktop/internal/agent"
	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/nativehost"
	"github.com/AndDev741/beConscient/desktop/internal/pairing"
	"github.com/AndDev741/beConscient/desktop/internal/paths"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/upload"
	"github.com/AndDev741/beConscient/desktop/internal/version"
)

const usage = `beconscient-agent: beConscient desktop agent

Usage:
  beconscient-agent [run] [--dev]          run the agent (default; started by systemd --user)
  beconscient-agent pair --server URL --key FILE [--name NAME] [--dev]
                                           pair with the server; the code is read from stdin
  beconscient-agent status                 show state, queue, last upload and sensors
  beconscient-agent replay --since DATE    re-send acknowledged events from DATE onward
  beconscient-agent probe [--seconds N]    read-only D-Bus check: idle time, lock and inhibitor state
  beconscient-agent version

--dev accepts the public test-only server key (development only).
`

func main() {
	unix.Umask(0o077)
	args := os.Args[1:]
	if nativehost.IsHostInvocation(args) {
		os.Exit(runHost(args))
	}
	if len(args) == 0 || strings.HasPrefix(args[0], "-") {
		args = append([]string{"run"}, args...)
	}
	var code int
	switch args[0] {
	case "run":
		code = runAgent(args[1:])
	case "pair":
		code = runPair(args[1:], os.Stdin)
	case "status":
		code = runStatus()
	case "replay":
		code = runReplay(args[1:])
	case "probe":
		code = runProbe(args[1:])
	case "version":
		fmt.Println(version.Version)
	case "help":
		fmt.Print(usage)
	default:
		fmt.Fprint(os.Stderr, usage)
		code = 2
	}
	os.Exit(code)
}

func logger() *slog.Logger {
	return slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelInfo}))
}

func fail(format string, a ...interface{}) int {
	fmt.Fprintf(os.Stderr, "beconscient-agent: "+format+"\n", a...)
	return 1
}

// runHost is native messaging host mode.
func runHost(args []string) int {
	log := logger().With("mode", "native-host")
	if err := nativehost.CheckOrigin(args[0]); err != nil {
		log.Error("refusing to start", "err", err)
		return 1
	}
	h := &nativehost.Host{
		In: os.Stdin, Out: os.Stdout, Log: log, SocketPath: paths.SocketPath(),
		OpenQueue: func() (*queue.Queue, error) {
			p, err := paths.QueuePath()
			if err != nil {
				return nil, err
			}
			return queue.Open(p)
		},
	}
	if err := h.Run(); err != nil {
		log.Error("native host stopped", "err", err)
		return 1
	}
	return 0
}

func loadConfig() (*config.Config, error) {
	p, err := paths.ConfigPath()
	if err != nil {
		return nil, err
	}
	return config.Load(p)
}

func openQueue() (*queue.Queue, error) {
	p, err := paths.QueuePath()
	if err != nil {
		return nil, err
	}
	return queue.Open(p)
}

func runAgent(args []string) int {
	fs := flag.NewFlagSet("run", flag.ContinueOnError)
	dev := fs.Bool("dev", false, "accept the public test-only server key")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	log := logger()
	cfg, err := loadConfig()
	switch {
	case errors.Is(err, os.ErrNotExist):
		log.Warn("not paired yet: measuring and queueing; run `beconscient-agent pair` to start uploading")
	case err != nil:
		return fail("%v", err)
	default:
		if err := config.CheckKey(cfg.Key(), *dev); err != nil {
			return fail("%v", err)
		}
	}
	q, err := openQueue()
	if err != nil {
		return fail("open queue: %v", err)
	}
	defer q.Close()

	src, err := activity.NewDBusSource(log, true)
	if err != nil {
		return fail("%v", err)
	}
	defer src.Close()

	up := &upload.Uploader{Q: q, LoadConfig: loadConfig, Log: log.With("part", "upload"), Dev: *dev}
	a := agent.New(agent.Options{
		Queue: q, Source: src, Delayer: src, Log: log, SocketPath: paths.SocketPath(), Uploader: up,
	})
	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGTERM, syscall.SIGINT)
	defer stop()
	log.Info("beconscient-agent starting", "version", version.Version, "queue", q.Path())
	if err := a.Run(ctx); err != nil {
		return fail("%v", err)
	}
	log.Info("beconscient-agent stopped")
	return 0
}

func runPair(args []string, stdin io.Reader) int {
	fs := flag.NewFlagSet("pair", flag.ContinueOnError)
	server := fs.String("server", "", "server base URL (https)")
	keyArg := fs.String("key", "", "server public key JSON file (from tools/cmd/keygen)")
	name := fs.String("name", "Linux desktop", "name suggested for this device")
	dev := fs.Bool("dev", false, "accept the public test-only server key and http URLs")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if fs.NArg() > 0 {
		return fail("unexpected argument %q: the pairing code is read from standard input, never from the command line", fs.Arg(0))
	}
	if *server == "" || *keyArg == "" {
		return fail("pair needs --server and --key")
	}
	var keyData []byte
	if strings.HasPrefix(strings.TrimSpace(*keyArg), "{") {
		keyData = []byte(*keyArg)
	} else {
		var err error
		keyData, err = os.ReadFile(*keyArg)
		if err != nil {
			return fail("read key: %v", err)
		}
	}
	key, err := config.ParseKey(keyData)
	if err != nil {
		return fail("%v", err)
	}
	if err := config.CheckKey(key, *dev); err != nil {
		return fail("%v", err)
	}
	if f, ok := stdin.(*os.File); ok && isTerminal(f) {
		fmt.Fprint(os.Stderr, "Pairing code (XXXX-XXXX): ")
	}
	code, err := pairing.ReadCode(stdin)
	if err != nil {
		return fail("%v", err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Minute)
	defer cancel()
	cfg, err := pairing.Pair(ctx, pairing.Request{Server: *server, Key: key, Code: code, Name: *name, Dev: *dev})
	if err != nil {
		return fail("%v", err)
	}
	p, err := paths.ConfigPath()
	if err != nil {
		return fail("%v", err)
	}
	if err := config.Save(p, cfg); err != nil {
		return fail("save %s: %v", p, err)
	}
	fmt.Printf("Paired as %q (device %s).\n", cfg.DeviceName, cfg.DeviceID)
	fmt.Printf("Fingerprint: %s\nCheck that the page shows the same fingerprint for this installation.\n", cfg.Fingerprint)
	fmt.Printf("Configuration saved to %s\n", p)
	return 0
}

func isTerminal(f *os.File) bool {
	_, err := unix.IoctlGetTermios(int(f.Fd()), unix.TCGETS)
	return err == nil
}

func runStatus() int {
	q, err := openQueue()
	if err != nil {
		return fail("open queue: %v", err)
	}
	defer q.Close()
	cfg, cerr := loadConfig()
	if errors.Is(cerr, os.ErrNotExist) {
		cerr = nil
	}
	err = agent.WriteStatus(os.Stdout, agent.StatusInput{
		Queue: q, Config: cfg, ConfigErr: cerr, SocketPath: paths.SocketPath(), Now: time.Now(),
	})
	if err != nil {
		return fail("%v", err)
	}
	return 0
}

// parseSince accepts a local date (YYYY-MM-DD, from local midnight) or an
// RFC 3339 timestamp.
func parseSince(s string) (time.Time, error) {
	if t, err := time.ParseInLocation("2006-01-02", s, time.Local); err == nil {
		return t, nil
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t, nil
	}
	return time.Time{}, fmt.Errorf("--since %q: use YYYY-MM-DD or an RFC 3339 time", s)
}

func runReplay(args []string) int {
	fs := flag.NewFlagSet("replay", flag.ContinueOnError)
	since := fs.String("since", "", "re-send acknowledged events that end at or after this date")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *since == "" {
		return fail("replay needs --since")
	}
	t, err := parseSince(*since)
	if err != nil {
		return fail("%v", err)
	}
	q, err := openQueue()
	if err != nil {
		return fail("open queue: %v", err)
	}
	defer q.Close()
	n, err := q.Replay(t)
	if err != nil {
		return fail("%v", err)
	}
	fmt.Printf("Re-queued %d acknowledged events since %s. The agent sends them within a minute; the server answers \"duplicate\" for those it already has.\n",
		n, t.Local().Format(time.RFC3339))
	return 0
}

// runProbe is a read-only D-Bus check. It never takes an inhibitor.
func runProbe(args []string) int {
	fs := flag.NewFlagSet("probe", flag.ContinueOnError)
	seconds := fs.Int("seconds", 30, "how long to watch")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	src, err := activity.NewDBusSource(logger(), true)
	if err != nil {
		return fail("%v", err)
	}
	defer src.Close()
	fmt.Printf("logind session for LockedHint: %s\n", src.SessionPath())
	show := func() {
		idle, ierr := src.IdleTime()
		ss, sserr := src.ScreenSaverActive()
		lh, lherr := src.LockedHint()
		inh, inherr := src.IdleInhibited()
		fmt.Printf("%s idle=%s screensaver_active=%s locked_hint=%s idle_inhibited=%s\n",
			time.Now().Format("15:04:05"), orErr(idle.Round(time.Millisecond), ierr), orErr(ss, sserr), orErr(lh, lherr), orErr(inh, inherr))
	}
	show()
	deadline := time.After(time.Duration(*seconds) * time.Second)
	tick := time.NewTicker(agent.PollEvery)
	defer tick.Stop()
	for {
		select {
		case <-deadline:
			return 0
		case <-tick.C:
			show()
		case sig := <-src.Signals():
			fmt.Printf("%s signal %s value=%t\n", time.Now().Format("15:04:05"), sig.Kind, sig.Value)
		}
	}
}

func orErr(v interface{}, err error) string {
	if err != nil {
		return "error(" + err.Error() + ")"
	}
	return fmt.Sprint(v)
}

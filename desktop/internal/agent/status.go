package agent

import (
	"fmt"
	"io"
	"sort"
	"time"

	"github.com/AndDev741/beConscient/desktop/internal/config"
	"github.com/AndDev741/beConscient/desktop/internal/health"
	"github.com/AndDev741/beConscient/desktop/internal/nativehost"
	"github.com/AndDev741/beConscient/desktop/internal/queue"
	"github.com/AndDev741/beConscient/desktop/internal/upload"
	"github.com/AndDev741/beConscient/desktop/internal/version"
)

// StatusInput is what `status` reads. Nothing here talks to the server.
type StatusInput struct {
	Queue      *queue.Queue
	Config     *config.Config // nil when not paired
	ConfigErr  error
	SocketPath string
	Now        time.Time
}

func ago(now time.Time, ms int64) string {
	if ms <= 0 {
		return "never"
	}
	d := now.Sub(time.UnixMilli(ms)).Round(time.Second)
	if d < 0 {
		d = 0
	}
	return fmt.Sprintf("%s (%s ago)", time.UnixMilli(ms).Local().Format("2006-01-02 15:04:05"), d)
}

// WriteStatus prints the agent status: state, queue, last upload, sensors.
func WriteStatus(w io.Writer, in StatusInput) error {
	fmt.Fprintf(w, "beconscient-agent %s\n", version.Version)

	running := false
	if in.SocketPath != "" {
		if c, err := nativehost.Dial(in.SocketPath); err == nil {
			running = true
			c.Close()
		}
	}
	if running {
		fmt.Fprintf(w, "Agent:        running (socket %s)\n", in.SocketPath)
	} else {
		fmt.Fprintln(w, "Agent:        not running")
	}

	var st StateRecord
	if ok, _ := in.Queue.MetaJSON(MetaState, &st); ok {
		label := st.State
		if !running || !st.Running {
			label = "last known " + st.State
		}
		fmt.Fprintf(w, "State:        %s since %s\n", label, ago(in.Now, st.Since))
		fmt.Fprintf(w, "              locked=%t idle-inhibitor=%t media-playing=%t playing=%t, updated %s\n",
			st.Locked, st.Inhibited, st.Media, st.Playing, ago(in.Now, st.UpdatedAt))
	} else {
		fmt.Fprintln(w, "State:        unknown (the agent has not run yet)")
	}

	switch {
	case in.Config != nil:
		fmt.Fprintf(w, "Paired:       yes, installation %s, fingerprint %s\n", in.Config.InstallationID, in.Config.Fingerprint)
		fmt.Fprintf(w, "Server:       %s (key %s)\n", in.Config.Server, in.Config.Kid)
		if in.Config.Key().IsTest() {
			fmt.Fprintln(w, "              WARNING: test-only key; uploads need --dev")
		}
	case in.ConfigErr != nil:
		fmt.Fprintf(w, "Paired:       no (%v)\n", in.ConfigErr)
	default:
		fmt.Fprintln(w, "Paired:       no")
	}

	stats, err := in.Queue.Stats()
	if err != nil {
		return err
	}
	oldest := "none"
	if stats.Pending > 0 && stats.OldestPendingSince > 0 {
		oldest = in.Now.Sub(time.UnixMilli(stats.OldestPendingSince)).Round(time.Second).String()
	}
	fmt.Fprintf(w, "Queue:        %d pending (oldest unsent %s), %d acknowledged (kept 14 days), %d rejected\n",
		stats.Pending, oldest, stats.Acked, stats.Rejected)
	if stats.Frozen != nil {
		fmt.Fprintf(w, "Batch:        seq %d frozen with %d events, %d attempts\n", stats.Frozen.Seq, len(stats.Frozen.Items), stats.Frozen.Attempts)
	}

	var up upload.Status
	if ok, _ := in.Queue.MetaJSON(upload.MetaKey, &up); ok {
		fmt.Fprintf(w, "Last upload:  %s", ago(in.Now, up.LastSuccessAt))
		if up.LastSeq > 0 {
			fmt.Fprintf(w, ", seq %d", up.LastSeq)
		}
		fmt.Fprintln(w)
		if up.LastError != "" && up.LastAttemptAt > up.LastSuccessAt {
			fmt.Fprintf(w, "Last error:   %s at %s, %d failures in a row, next try %s\n",
				up.LastError, ago(in.Now, up.LastAttemptAt), up.Failures, time.UnixMilli(up.NextAttemptAt).Local().Format("15:04:05"))
		}
	} else {
		fmt.Fprintln(w, "Last upload:  never")
	}

	fmt.Fprintln(w, "Sensors:")
	var hr HealthRecord
	sensors := map[string]string{}
	if ok, _ := in.Queue.MetaJSON(MetaHealth, &hr); ok {
		for name, s := range hr.Sensors {
			line := s.State
			if s.Detail != "" {
				line += " (" + s.Detail + ")"
			}
			sensors[name] = line
		}
		fmt.Fprintf(w, "  last health record %s\n", ago(in.Now, hr.At))
	}
	// Extension heartbeats are read live, so they show even when the agent
	// is down and the host wrote them directly.
	if hbs, err := in.Queue.Heartbeats(); err == nil {
		for _, browser := range []string{"chrome", "brave"} {
			name, _ := health.SensorForBrowser(browser)
			var hb *queue.Heartbeat
			if h, ok := hbs[browser]; ok {
				hb = &h
			}
			s := health.ExtensionSensor(hb, in.Now)
			line := s.State
			if s.Detail != "" {
				line += " (" + s.Detail + ")"
			}
			sensors[name] = line
		}
	}
	names := make([]string, 0, len(sensors))
	for n := range sensors {
		names = append(names, n)
	}
	sort.Strings(names)
	for _, n := range names {
		fmt.Fprintf(w, "  %-17s %s\n", n, sensors[n])
	}
	return nil
}

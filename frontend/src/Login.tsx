import { useState, type FormEvent } from 'react';
import { api } from './api';
import { errorText } from './ui';

export function Login({ onSignedIn }: { onSignedIn: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api.login(username, password);
      onSignedIn();
    } catch (err) {
      setError(errorText(err));
      setBusy(false);
    }
  }

  return (
    <div className="loginwrap">
      <form className="loginbox" onSubmit={submit}>
        <div className="logo big">
          <span className="mark" aria-hidden="true" />
          beConscient
        </div>
        <p className="help">Your screen time, measured on your own server.</p>
        <div className="field">
          <label htmlFor="u">Username</label>
          <input id="u" className="input" autoComplete="username" autoCapitalize="none"
                 value={username} onChange={(e) => setUsername(e.target.value)} />
        </div>
        <div className="field">
          <label htmlFor="p">Password</label>
          <input id="p" className="input" type="password" autoComplete="current-password"
                 value={password} onChange={(e) => setPassword(e.target.value)} />
        </div>
        {error && <p className="err" role="alert">{error}</p>}
        <button className="btn primary" type="submit" disabled={busy || !username || !password}>
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}

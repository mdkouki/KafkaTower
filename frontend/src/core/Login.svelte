<script>
	import { fly } from 'svelte/transition';
	import { motionParams } from './motion.js';
	import { authUser, initAuth, authError } from './auth.js';

	export let authMode;

	let username = '';
	let password = '';
	let isLoading = false;

	function handleLogin() {
		const form = document.createElement('form');
		form.method = 'POST';
		form.action = '/login';

		const usernameInput = document.createElement('input');
		usernameInput.type = 'hidden';
		usernameInput.name = 'username';
		usernameInput.value = username;

		const passwordInput = document.createElement('input');
		passwordInput.type = 'hidden';
		passwordInput.name = 'password';
		passwordInput.value = password;

		form.appendChild(usernameInput);
		form.appendChild(passwordInput);
		document.body.appendChild(form);
		form.submit();
	}

	function handleKeyDown(e) {
		if (e.key === 'Enter' && !isLoading && authMode !== 'oidc') {
			handleLogin();
		}
	}
</script>

<div class="login-container">
	<div class="login-glow" aria-hidden="true"></div>

	<div class="login-box" in:fly={motionParams({ y: 16, duration: 320 })}>
		<div class="brand">
			<svg width="32" height="32" viewBox="0 0 28 28" fill="none">
				<rect width="28" height="28" rx="8" fill="var(--color-accent)"/>
				<rect x="9" y="12" width="10" height="11" rx="1" fill="#081019"/>
				<rect x="9" y="9" width="2.4" height="3" fill="#081019"/>
				<rect x="12.8" y="9" width="2.4" height="3" fill="#081019"/>
				<rect x="16.6" y="9" width="2.4" height="3" fill="#081019"/>
				<rect x="13" y="16" width="2" height="3" fill="var(--color-accent)"/>
				<line x1="14" y1="9" x2="14" y2="5" stroke="#081019" stroke-width="1.3" stroke-linecap="round"/>
				<circle cx="14" cy="5" r="1" fill="#081019"/>
			</svg>
			<h1>KafkaTower</h1>
		</div>
		<p class="subtitle">Sign in to continue to your Kafka operations console</p>

		{#if $authError}
			<div class="error-message" in:fly={motionParams({ y: -6, duration: 180 })}>
				<svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
					<circle cx="12" cy="12" r="10"/>
					<line x1="12" y1="8" x2="12" y2="12"/>
					<line x1="12" y1="16" x2="12.01" y2="16"/>
				</svg>
				{$authError}
			</div>
		{/if}

		{#if authMode === 'oidc'}
			<a href="/oauth2/authorization/oidc" class="btn btn-primary">
				Continue with SSO
			</a>
		{:else}
			<form on:submit|preventDefault={handleLogin}>
				<div class="form-group">
					<label for="username">Username</label>
					<input
						id="username"
						type="text"
						bind:value={username}
						disabled={isLoading}
						placeholder="Enter your username"
						on:keydown={handleKeyDown}
						required
					/>
				</div>

				<div class="form-group">
					<label for="password">Password</label>
					<input
						id="password"
						type="password"
						bind:value={password}
						disabled={isLoading}
						placeholder="Enter your password"
						on:keydown={handleKeyDown}
						required
					/>
				</div>

				<button type="submit" class="btn btn-primary" disabled={isLoading}>
					{isLoading ? 'Signing in…' : 'Sign in'}
				</button>
			</form>
		{/if}
	</div>
</div>

<style>
	.login-container {
		position: relative;
		display: flex;
		justify-content: center;
		align-items: center;
		height: 100vh;
		background: var(--color-bg);
		overflow: hidden;
	}

	.login-glow {
		position: absolute;
		inset: 0;
		background:
			radial-gradient(560px circle at 18% 20%, rgba(34, 211, 238, 0.14), transparent 60%),
			radial-gradient(480px circle at 85% 80%, rgba(167, 139, 250, 0.10), transparent 60%);
		pointer-events: none;
	}

	.login-box {
		position: relative;
		background: var(--color-surface);
		border: 1px solid var(--color-border);
		padding: 36px 36px 32px;
		border-radius: var(--radius-lg);
		box-shadow: var(--shadow-lg);
		width: 100%;
		max-width: 380px;
	}

	.brand {
		display: flex;
		align-items: center;
		justify-content: center;
		gap: 10px;
		margin-bottom: 6px;
	}

	h1 {
		margin: 0;
		text-align: center;
		color: var(--color-text-primary);
		font-size: 20px;
		font-weight: 700;
	}

	.subtitle {
		text-align: center;
		color: var(--color-text-muted);
		margin: 0 0 26px;
		font-size: 13px;
		line-height: 1.5;
	}

	.error-message {
		display: flex;
		align-items: center;
		gap: 8px;
		background: rgba(248, 113, 113, 0.1);
		border: 1px solid rgba(248, 113, 113, 0.3);
		color: var(--color-danger);
		padding: 10px 12px;
		border-radius: var(--radius-sm);
		margin-bottom: 18px;
		font-size: 13px;
	}

	form {
		display: flex;
		flex-direction: column;
		gap: 16px;
	}

	.form-group {
		display: flex;
		flex-direction: column;
		gap: 6px;
	}

	label {
		color: var(--color-text-secondary);
		font-weight: 500;
		font-size: 12.5px;
	}

	input {
		padding: 10px 12px;
		border: 1px solid var(--color-border);
		border-radius: var(--radius-sm);
		font-size: 14px;
		background: var(--color-surface-sunken);
		color: var(--color-text-primary);
		transition: border-color var(--duration-base) ease, box-shadow var(--duration-base) ease;
	}

	input::placeholder { color: var(--color-text-muted); }

	input:focus {
		outline: none;
		border-color: var(--color-accent);
		box-shadow: 0 0 0 3px var(--color-accent-light);
	}

	input:disabled {
		opacity: 0.6;
		cursor: not-allowed;
	}

	.btn {
		padding: 11px 16px;
		border: none;
		border-radius: var(--radius-sm);
		font-size: 14px;
		font-weight: 600;
		cursor: pointer;
		transition: transform var(--duration-fast) var(--ease-spring), box-shadow var(--duration-base) ease, filter var(--duration-base) ease;
		text-decoration: none;
		display: inline-block;
		text-align: center;
		margin-top: 4px;
	}

	.btn-primary {
		background: var(--gradient-accent);
		color: #081019;
	}

	.btn-primary:hover:not(:disabled) {
		filter: brightness(1.08);
		box-shadow: var(--shadow-glow);
	}

	.btn-primary:active:not(:disabled) {
		transform: scale(0.98);
	}

	.btn-primary:disabled {
		opacity: 0.6;
		cursor: not-allowed;
	}
</style>

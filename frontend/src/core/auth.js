import { writable } from 'svelte/store';

export const authUser = writable(null); // null = loading, false = not logged in, { username, role, authMode } = logged in
export const authMode = writable(null); // 'jaas' | 'ldap' | 'oidc'
export const authError = writable(null);

export async function initAuth() {
	try {
		const configRes = await fetch('/api/auth/config', { credentials: 'include' });
		const configData = await configRes.json();
		authMode.set(configData.mode);

		const meRes = await fetch('/api/auth/me', { credentials: 'include' });
		if (meRes.ok) {
			const userData = await meRes.json();
			authUser.set({
				username: userData.username,
				role: userData.role,
				authMode: userData.authMode
			});
		} else if (meRes.status === 401) {
			authUser.set(false);
		} else {
			authUser.set(false);
			authError.set('Failed to fetch user info');
		}
	} catch (error) {
		console.error('Auth init failed:', error);
		authUser.set(false);
		authError.set('Auth initialization failed');
	}
}

export async function logout() {
	try {
		await fetch('/api/auth/logout', { method: 'POST', credentials: 'include' });
		authUser.set(false);
		authError.set(null);
		window.location.href = '/';
	} catch (error) {
		console.error('Logout failed:', error);
		authUser.set(false);
	}
}

import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

const origin = window.location.origin

export const oidcManager = new UserManager({
  authority:
    import.meta.env.VITE_OIDC_AUTHORITY ??
    'http://localhost:8180/realms/flowforge',
  client_id: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'flowforge-web',
  redirect_uri: `${origin}/auth/callback`,
  post_logout_redirect_uri: origin,
  response_type: 'code',
  scope: 'openid',
  automaticSilentRenew: true,
  userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
})

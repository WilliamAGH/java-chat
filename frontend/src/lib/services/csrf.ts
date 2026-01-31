/** Spring Security's default CSRF cookie name for SPA clients. */
const CSRF_COOKIE_NAME = 'XSRF-TOKEN'

/** Header name expected by Spring Security's CsrfTokenRequestAttributeHandler. */
const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

function readCookie(cookieName: string): string | null {
  if (typeof document === 'undefined') {
    return null
  }
  const cookieEntry = document.cookie
    .split(';')
    .map((entry) => entry.trim())
    .find((entry) => entry.startsWith(`${cookieName}=`))

  if (!cookieEntry) {
    return null
  }

  const tokenText = cookieEntry.slice(cookieName.length + 1)
  return tokenText ? decodeURIComponent(tokenText) : null
}

export function csrfHeader(): Record<string, string> {
  const tokenText = readCookie(CSRF_COOKIE_NAME)
  if (!tokenText) {
    return {}
  }
  return { [CSRF_HEADER_NAME]: tokenText }
}

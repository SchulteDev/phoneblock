package de.haumacher.phoneblock.app;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.haumacher.phoneblock.db.DB;
import de.haumacher.phoneblock.db.DBService;
import de.haumacher.phoneblock.db.settings.AuthToken;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

public abstract class LoginFilter implements Filter {

	private static final Logger LOG = LoggerFactory.getLogger(LoginFilter.class);
	
	/**
	 * Name of the persistent cookie to identifiy a user that has enabled the "remember me" feature.
	 */
	private static final String LOGIN_COOKIE = "pb-login";
	
	private static final int ONE_YEAR_SECONDS = 60*60*24*365;

	/**
	 * Session attribute storing the ID of the authorization token used to log in.
	 */
	public static final String AUTHORIZATION_ATTR = "tokenId";

	private static final String BEARER_AUTH = "Bearer ";

	public static final String AUTHENTICATED_USER_ATTR = "authenticated-user";
	
	@Override
	public void init(FilterConfig filterConfig) throws ServletException {
		// For backwards-compatibility.
	}

	@Override
	public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
		HttpServletRequest req = (HttpServletRequest) request;
		HttpServletResponse resp = (HttpServletResponse) response;
		
		if (allowSessionAuth(req)) {
			// Short-cut to prevent authenticating every request.
			HttpSession session = req.getSession(false);
			if (session != null) {
				String userName = LoginFilter.getAuthenticatedUser(session);
				if (userName != null) {
					req.setAttribute(LoginFilter.AUTHENTICATED_USER_ATTR, userName);
					chain.doFilter(request, response);
					return;
				}
			}
		}

		DB db = DBService.getInstance();
		
		if (allowCookieAuth(req)) {
			Cookie[] cookies = req.getCookies();
			if (cookies != null) {
				for (Cookie cookie : cookies) {
					if (LOGIN_COOKIE.equals(cookie.getName())) {
						String token = cookie.getValue();
						AuthToken authorization = db.checkAuthToken(token, System.currentTimeMillis(), req.getHeader("User-Agent"), true);
						if (authorization != null && checkTokenAuthorization(req, authorization)) {
							String userName = authorization.getUserName();
							LOG.info("Accepted login token for user {}.", userName);
							
							setUser(req, userName);
							
							// Update cookie to extend lifetime and invalidate old version of cookie. 
							// This enhances security since a token can be used only once.
							setLoginCookie(req, resp, authorization);
							
							chain.doFilter(request, response);
							return;
						} else {
							if (authorization == null) {
								LOG.info("Dropping outdated login cookie: {}", token);
							} else {
								LOG.info("Login not allowed with cookie: {}", token);
							}
							removeLoginCookie(req, resp);
						}
						break;
					}
				}
			}
		}
		
		String authHeader = req.getHeader("Authorization");
		if (authHeader != null) {
			if (authHeader.startsWith(BEARER_AUTH)) {
				String token = authHeader.substring(BEARER_AUTH.length());
				
				AuthToken authorization = db.checkAuthToken(token, System.currentTimeMillis(), req.getHeader("User-Agent"), false);
				if (authorization != null) {
					String userName = authorization.getUserName();
					
					if (checkTokenAuthorization(req, authorization)) {
						LOG.info("Accepted bearer token {}... for user {}.", token.substring(0, 16), userName);
						
						setUser(req, userName);
						
						chain.doFilter(request, response);
						return;
					} else {
						LOG.info("Access to {} with bearer token {}... rejected due to privilege mismatch for user {}.", 
								req.getServletPath(), token.substring(0, 16), userName);
					}
				}
			} else if (allowBasicAuth(req)) {
				String userName = db.basicAuth(authHeader);
				if (userName != null) {
					setUser(req, userName);
					chain.doFilter(request, response);
					return;
				}
			}
		}
		
		requestLogin(req, resp, chain);
	}

	protected boolean allowBasicAuth(HttpServletRequest req) {
		return true;
	}

	protected boolean allowSessionAuth(HttpServletRequest req) {
		return true;
	}

	protected boolean allowCookieAuth(HttpServletRequest req) {
		return true;
	}

	protected void setUser(HttpServletRequest req, String userName) {
		setSessionUser(req, userName);
	}

	/**
	 * Whether the given authorization token is valid for performing a login in the concrete situation.
	 */
	protected abstract boolean checkTokenAuthorization(HttpServletRequest request, AuthToken authorization);
	
	/**
	 * Handles the request, if no authentication was provided.
	 */
	protected abstract void requestLogin(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException;

	@Override
	public void destroy() {
		// For backwards-compatibility.
	}
	
	/**
	 * Sets or updats a cookie with a login token.
	 */
	public static void setLoginCookie(HttpServletRequest req, HttpServletResponse resp, AuthToken authorization) {
		Cookie loginCookie = new Cookie(LOGIN_COOKIE, authorization.getToken());
		loginCookie.setPath(req.getContextPath());
		loginCookie.setHttpOnly(true);
		loginCookie.setSecure(true);
		loginCookie.setAttribute("SameSite", "Strict");
		loginCookie.setMaxAge(ONE_YEAR_SECONDS);
		resp.addCookie(loginCookie);
		
		req.getSession().setAttribute(AUTHORIZATION_ATTR, authorization);
	}

	/**
	 * Removes an authorization token set during login.
	 */
	public static void removePersistentLogin(HttpServletRequest request, HttpServletResponse response) {
		// Invalidate authorization token.
		HttpSession session = request.getSession(false);
		if (session != null) {
			AuthToken token = (AuthToken) session.getAttribute(AUTHORIZATION_ATTR);
			if (token != null) {
				DBService.getInstance().invalidateAuthToken(token.getId());
			}
		}
		
		removeLoginCookie(request, response);
	}

	private static void removeLoginCookie(HttpServletRequest request, HttpServletResponse response) {
		for (Cookie cookie : request.getCookies()) {
			if (LOGIN_COOKIE.equals(cookie.getName())) {
				Cookie removal = new Cookie(LOGIN_COOKIE, "");
				removal.setMaxAge(0);
				response.addCookie(removal);
			}
		}
	}

	/**
	 * Retrieves the authenticated user from a request attribute set by a {@link LoginFilter} for the service URI.
	 * 
	 * @see BasicLoginFilter
	 */
	public static String getAuthenticatedUser(HttpServletRequest req) {
		return (String) req.getAttribute(AUTHENTICATED_USER_ATTR);
	}
	
	/** 
	 * The authenticated user of the given session.
	 */
	public static String getAuthenticatedUser(HttpSession session) {
		if (session == null) {
			return null;
		}
		return (String) session.getAttribute(AUTHENTICATED_USER_ATTR);
	}

	/** 
	 * Adds the given user name to the request and session.
	 */
	public static void setSessionUser(HttpServletRequest req, String userName) {
		setRequestUser(req, userName);
		req.getSession().setAttribute(AUTHENTICATED_USER_ATTR, userName);
	}

	/** 
	 * Adds the given user name to the current request.
	 */
	public static void setRequestUser(HttpServletRequest req, String userName) {
		req.setAttribute(AUTHENTICATED_USER_ATTR, userName);
	}

}

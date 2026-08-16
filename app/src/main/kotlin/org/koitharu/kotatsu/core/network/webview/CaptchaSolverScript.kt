package org.koitharu.kotatsu.core.network.webview

import org.koitharu.kotatsu.core.network.tls.ChromeTlsIdentity

/**
 * JavaScript code injected into the WebView to automatically solve
 * CloudFlare JS challenges (Turnstile, Managed Challenge, and generic checkbox challenges).
 *
 * Notes on modern CloudFlare:
 * - Many Managed Challenges complete purely via JS fingerprinting (no click needed).
 * - Turnstile widgets are cross-origin iframes; contentDocument is usually inaccessible.
 * - We therefore combine: stealth anti-detection, synthetic pointer events on widgets,
 *   label/checkbox clicks, shadow-DOM probing, form submits, and a continuous retry loop.
 *
 * The stealth script's job is *consistency*, not disguise. Cloudflare does not score a single
 * signal; it correlates the UA against `navigator.platform`, `userAgentData`, touch points,
 * `screen`, the WebGL renderer and the pointer/hover media queries. Claiming desktop Chrome from
 * an Android [android.webkit.WebView] loses that comparison every time — the WebView cannot hide
 * touch support or its GPU — and clearance issued to a contradictory client is rejected on the
 * very next request, which is the loop this file exists to end. So [stealthScript] derives every
 * value it defines from the UA it is given ([org.koitharu.kotatsu.core.network.tls.ChromeTlsIdentity.USER_AGENT],
 * an Android Chrome UA), and leaves anything it cannot make consistent alone. It only masks the
 * signals that are automation tells rather than platform facts: `navigator.webdriver`, the missing
 * `window.chrome`, an empty plugin list.
 *
 * Must be injected at document-start (before any page script, in every frame).
 */
internal object CaptchaSolverScript {

	/**
	 * Anti-detection stealth script tailored to [userAgent]. Masks every JS signal
	 * Cloudflare Turnstile correlates against the User-Agent so the WebView looks like
	 * the same browser the OkHttp stack claims to be.
	 *
	 * Must be injected at document-start (e.g. via
	 * [androidx.webkit.WebViewCompat.addDocumentStartJavaScript]) so it runs before the
	 * page's own scripts read the real (Android) fingerprints.
	 */
	fun stealthScript(userAgent: String): String = buildStealthScript(userAgent)

	/** The canonical script for [ChromeTlsIdentity.USER_AGENT] — the same Android Chrome the network stack sends. */
	@Suppress("ObjectPropertyName")
	val STEALTH_SCRIPT: String = stealthScript(ChromeTlsIdentity.USER_AGENT)

	/**
	 * Renders [value] as a JavaScript string literal.
	 *
	 * The UA has to reach the script as data, not as concatenated source: an unescaped quote or
	 * backslash would break the whole IIFE, and a broken stealth script fails *silently* — the
	 * WebView reports its real Android identity while the network stack claims something else, which
	 * is precisely the contradiction Cloudflare punishes. Not hypothetical: the previous revision
	 * built this line from a JS-style `JSON.stringify(ua)` template that Kotlin never interpolated,
	 * so every injection threw a SyntaxError and no stealth was ever applied.
	 */
	private fun jsString(value: String): String = buildString(value.length + 2) {
		append('"')
		for (c in value) {
			when (c) {
				'"' -> append("\\\"")
				'\\' -> append("\\\\")
				'\n' -> append("\\n")
				'\r' -> append("\\r")
				else -> if (c.code < 0x20 || c.code == 0x2028 || c.code == 0x2029) {
					// Control chars, plus the two separators that terminate a line for some parsers.
					append("\\u").append(c.code.toString(16).padStart(4, '0'))
				} else {
					append(c)
				}
			}
		}
		append('"')
	}

	private fun buildStealthScript(ua: String): String = """
		(function() {
			try {
				var reportedUA = ${jsString(ua)};

				// Client-hint values for an Android UA are read straight out of it, so
				// platform-version / model can never disagree with the UA string itself.
				var androidVersion = '14.0.0';
				var androidModel = 'K';
				try {
					var am = reportedUA.match(/Android (\d+(?:\.\d+)*)(?:; ([^;)]+))?/);
					if (am) {
						var parts = am[1].split('.');
						while (parts.length < 3) { parts.push('0'); }
						androidVersion = parts.slice(0, 3).join('.');
						if (am[2]) { androidModel = am[2].trim(); }
					}
				} catch (e) {}

				// Resolve a platform / client-hints identity consistent with the UA.
				var platform, chPlatform, mobile;
				if (reportedUA.indexOf('Windows') !== -1) {
					platform = 'Win32'; chPlatform = 'Windows'; mobile = false;
				} else if (reportedUA.indexOf('Macintosh') !== -1 || reportedUA.indexOf('Mac OS X') !== -1) {
					platform = 'MacIntel'; chPlatform = 'macOS'; mobile = false;
				} else if (reportedUA.indexOf('Android') !== -1) {
					platform = 'Linux armv8l'; chPlatform = 'Android'; mobile = true;
				} else if (reportedUA.indexOf('iPhone') !== -1 || reportedUA.indexOf('iPad') !== -1) {
					platform = 'iPhone'; chPlatform = 'iOS'; mobile = true;
				} else {
					platform = 'Linux x86_64'; chPlatform = 'Linux';
					mobile = reportedUA.indexOf('Mobile') !== -1;
				}

				// Define a getter on the prototype first (intercepts even
				// Object.getOwnPropertyDescriptor(proto, prop).get.call(navigator) bypasses)
				// and fall back to an own property on the navigator instance.
				var navProto = (window.Navigator && Navigator.prototype) || null;
				function defineGetter(obj, prop, getter) {
					try { Object.defineProperty(obj, prop, { get: getter, configurable: true }); return true; }
					catch (e) { return false; }
				}
				function defineNav(prop, getter) {
					if (navProto) { if (defineGetter(navProto, prop, getter)) return; }
					defineGetter(navigator, prop, getter);
				}
				function defineConst(obj, prop, value) {
					try { Object.defineProperty(obj, prop, { get: function(){ return value; }, configurable: true }); }
					catch (e) {}
				}

				// navigator.webdriver — the #1 automation signal. Real Chrome defines it and
				// reports false; `undefined` is itself unusual, so answer the way Chrome does.
				defineNav('webdriver', function() { return false; });

				// navigator.userAgent/appVersion — WebSettings already spoofs userAgent,
				// but redefine for belts-and-braces consistency.
				defineNav('userAgent', function() { return reportedUA; });
				defineNav('appVersion', function() {
					var i = reportedUA.indexOf('Mozilla/');
					return i === -1 ? reportedUA : reportedUA.substring(i + 'Mozilla/'.length);
				});
				defineNav('platform', function() { return platform; });
				defineNav('vendor', function() { return 'Google Inc.'; });
				defineNav('maxTouchPoints', function() { return mobile ? 5 : 0; });

				// window.chrome — present in real Chrome, absent in plain WebView.
				if (!window.chrome) {
					window.chrome = {
						runtime: {}, app: {},
						loadTimes: function() { return {}; },
						csi: function() { return {}; }
					};
				} else if (!window.chrome.runtime) {
					window.chrome.runtime = {};
				}

				// navigator.plugins — a *desktop* Chrome always exposes the bundled PDF viewer
				// entries, so an empty list there is a headless tell. Chrome on Android exposes
				// none at all, so on mobile the honest empty list is the consistent answer.
				if (!mobile) {
					defineNav('plugins', function() {
						var a = [1, 2, 3, 4, 5];
						a.item = function(i) { return this[i]; };
						a.namedItem = function() { return null; };
						a.refresh = function() {};
						return a;
					});
				}
				defineNav('languages', function() { return ['en-US', 'en']; });
				// CPU / memory: the device's real values are already plausible for the Android UA
				// we send, and a fixed pair would only add a contradiction (an 8-core, 8 GB phone
				// whose GPU and screen say otherwise). Override for a desktop UA only.
				if (!mobile) {
					defineNav('hardwareConcurrency', function() { return 8; });
					defineNav('deviceMemory', function() { return 8; });
				}

				// Permissions API — Cloudflare probes the notification permission.
				if (navigator.permissions && navigator.permissions.query) {
					var origQuery = navigator.permissions.query.bind(navigator.permissions);
					navigator.permissions.query = function(params) {
						if (params && params.name === 'notifications') {
							return Promise.resolve({ state: 'prompt', onchange: null });
						}
						return origQuery(params);
					};
				}

				// navigator.userAgentData (Client Hints) — strongly checked by Turnstile.
				try {
					var m = reportedUA.match(/Chrome\/(\d+)/);
					var major = (m && m[1]) ? m[1] : '133';
					var brands = [
						{ brand: 'Not(A:Brand', version: '99' },
						{ brand: 'Google Chrome', version: major },
						{ brand: 'Chromium', version: major }
					];
					var platformVersion = chPlatform === 'Windows' ? '15.0.0'
						: chPlatform === 'macOS' ? '14.3.0'
						: chPlatform === 'Android' ? androidVersion : '10.0.0';
					// Architecture and model must agree with the platform: Chrome on Android sends
					// an empty architecture/bitness and the device model, never x86/64.
					var architecture = mobile ? '' : 'x86';
					var bitness = mobile ? '' : '64';
					var model = mobile ? androidModel : '';
					var uad = {
						brands: brands,
						mobile: mobile,
						platform: chPlatform,
						getHighEntropyValues: function() {
							return Promise.resolve({
								brands: brands,
								mobile: mobile,
								platform: chPlatform,
								platformVersion: platformVersion,
								architecture: architecture,
								bitness: bitness,
								model: model,
								uaFullVersion: major + '.0.0.0',
								fullVersionList: brands,
								wow64: false
							});
						},
						toJSON: function() { return { brands: brands, mobile: mobile, platform: chPlatform }; }
					};
					defineNav('userAgentData', function() { return uad; });
				} catch (e) {}

				// Screen metrics: only invented for a desktop UA. On Android the WebView's own
				// values already match the UA, and overriding them (1920x1080 at dpr 1 on a phone)
				// contradicts the pointer/hover media queries and touch support the WebView cannot
				// hide — the kind of mismatch that gets clearance revoked a request later.
				if (!mobile) {
					defineConst(screen, 'width', 1920);
					defineConst(screen, 'height', 1080);
					defineConst(screen, 'availWidth', 1920);
					defineConst(screen, 'availHeight', 1040);
					defineConst(screen, 'colorDepth', 24);
					defineConst(screen, 'pixelDepth', 24);
					defineConst(window, 'devicePixelRatio', 1);
				}

				// WebGL vendor / renderer. Only rewritten when it would otherwise contradict the UA:
				// for the Android UA we send, a real Adreno/Mali string is exactly right and must be
				// left alone. The one Android value worth replacing is the software renderer an
				// emulator or a GPU-less device reports — "SwiftShader" says "no real device" as
				// loudly as navigator.webdriver does.
				try {
					var VENDOR = 0x1F00, RENDERER = 0x1F01;
					var UNMASKED_VENDOR = 0x9245, UNMASKED_RENDERER = 0x9246;
					var webglVendor, webglRenderer;
					if (mobile) {
						webglVendor = 'Qualcomm';
						webglRenderer = 'Adreno (TM) 730';
					} else {
						webglVendor = 'Google Inc. (Intel)';
						webglRenderer =
							'ANGLE (Intel, Intel(R) UHD Graphics 630 Direct3D11 vs_5_0 ps_5_0, D3D11)';
					}
					function needsRewrite(actual) {
						if (!mobile) return true;
						if (typeof actual !== 'string' || actual === '') return true;
						var a = actual.toLowerCase();
						return a.indexOf('swiftshader') !== -1 ||
							a.indexOf('software') !== -1 ||
							a.indexOf('llvmpipe') !== -1 ||
							a.indexOf('mesa') !== -1;
					}
					function patch(proto) {
						if (!proto || !proto.getParameter) return;
						var orig = proto.getParameter.bind(proto);
						proto.getParameter = function(p) {
							try {
								if (p === VENDOR || p === UNMASKED_VENDOR) {
									var av = orig(p);
									return needsRewrite(av) ? webglVendor : av;
								}
								if (p === RENDERER || p === UNMASKED_RENDERER) {
									var ar = orig(p);
									return needsRewrite(ar) ? webglRenderer : ar;
								}
							} catch (e) {}
							return orig(p);
						};
					}
					if (window.WebGLRenderingContext) patch(WebGLRenderingContext.prototype);
					if (window.WebGL2RenderingContext) patch(WebGL2RenderingContext.prototype);
				} catch (e) {}

				// Hide our toString patches from detection.
				var origToString = Function.prototype.toString;
				Function.prototype.toString = function() {
					try {
						if (navigator.permissions && this === navigator.permissions.query) {
							return 'function query() { [native code] }';
						}
						if (navigator.userAgentData && this === navigator.userAgentData.getHighEntropyValues) {
							return 'function getHighEntropyValues() { [native code] }';
						}
					} catch (e) {}
					return origToString.call(this);
				};

				return 'stealth_applied';
			} catch (e) {
				return 'stealth_error: ' + (e && e.message ? e.message : String(e));
			}
		})();
	""".trimIndent()

	/**
	 * One-shot auto-solve pass. Returns a string status for debugging.
	 */
	val SOLVE_SCRIPT: String = """
		(function() {
			function dispatchClick(el) {
				if (!el) return false;
				try {
					var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : { left: 0, top: 0, width: 20, height: 20 };
					var clientX = rect.left + (rect.width / 2);
					var clientY = rect.top + (rect.height / 2);
					var opts = {
						bubbles: true,
						cancelable: true,
						view: window,
						clientX: clientX,
						clientY: clientY,
						screenX: clientX,
						screenY: clientY,
						pointerType: 'touch',
						pressure: 0.5,
						width: 20,
						height: 20
					};
					try {
						if (typeof window.TouchEvent === 'function') {
							var touch = new Touch({
								identifier: Date.now(),
								target: el,
								clientX: clientX,
								clientY: clientY,
								radiusX: 10,
								radiusY: 10,
								force: 0.5
							});
							el.dispatchEvent(new TouchEvent('touchstart', { bubbles: true, cancelable: true, touches: [touch], targetTouches: [touch], changedTouches: [touch] }));
							el.dispatchEvent(new TouchEvent('touchend', { bubbles: true, cancelable: true, touches: [], targetTouches: [], changedTouches: [touch] }));
						}
					} catch (te) {}
					el.dispatchEvent(new PointerEvent('pointerdown', opts));
					el.dispatchEvent(new MouseEvent('mousedown', opts));
					el.dispatchEvent(new PointerEvent('pointerup', opts));
					el.dispatchEvent(new MouseEvent('mouseup', opts));
					el.dispatchEvent(new MouseEvent('click', opts));
					if (typeof el.click === 'function') el.click();
					return true;
				} catch (e) {
					try { el.click(); return true; } catch (e2) { return false; }
				}
			}

			function queryDeep(root, selector) {
				var found = root.querySelector(selector);
				if (found) return found;
				var all = root.querySelectorAll('*');
				for (var i = 0; i < all.length; i++) {
					if (all[i].shadowRoot) {
						found = queryDeep(all[i].shadowRoot, selector);
						if (found) return found;
					}
				}
				return null;
			}

			function queryAllDeep(root, selector) {
				var results = Array.prototype.slice.call(root.querySelectorAll(selector));
				var all = root.querySelectorAll('*');
				for (var i = 0; i < all.length; i++) {
					if (all[i].shadowRoot) {
						results = results.concat(queryAllDeep(all[i].shadowRoot, selector));
					}
				}
				return results;
			}

			try {
				// Strategy 1: CloudFlare Turnstile iframe / widget host
				var turnstileHosts = queryAllDeep(document,
					'iframe[src*="challenges.cloudflare.com"], ' +
					'iframe[src*="turnstile"], ' +
					'iframe[title*="Cloudflare"], ' +
					'iframe[title*="Widget containing a Cloudflare"], ' +
					'div.cf-turnstile, div#turnstile-wrapper, div[id*="cf-turnstile"], ' +
					'div.cf-turnstile-wrapper, ' +
					'[data-turnstile-sitekey], [data-sitekey]'
				);
				for (var i = 0; i < turnstileHosts.length; i++) {
					var host = turnstileHosts[i];
					// Prefer same-origin content when available
					try {
						if (host.tagName === 'IFRAME') {
							var iframeDoc = host.contentDocument || (host.contentWindow && host.contentWindow.document);
							if (iframeDoc) {
								var checkbox = queryDeep(iframeDoc,
									'input[type="checkbox"], .cb-lb, #challenge-stage input, [role="checkbox"], .mark'
								);
								if (checkbox && dispatchClick(checkbox)) return 'turnstile_checkbox_clicked';
								var body = iframeDoc.body || iframeDoc.querySelector('body');
								if (body && dispatchClick(body)) return 'turnstile_body_clicked';
							}
						}
					} catch (e) { /* cross-origin */ }

					// Click the host / iframe itself (cross-origin common case)
					if (dispatchClick(host)) return 'turnstile_host_clicked';

					// Also try parent label / wrapper
					var parent = host.parentElement;
					if (parent && dispatchClick(parent)) return 'turnstile_parent_clicked';
				}

				// Strategy 2: CloudFlare Managed Challenge checkbox / label
				var challengeCheckbox = queryDeep(document,
					'#challenge-stage input[type="checkbox"], ' +
					'#challenge-stage .ctp-checkbox-label, ' +
					'.ctp-checkbox-label, ' +
					'.challenge-form input[type="checkbox"], ' +
					'#cf-challenge input[type="checkbox"], ' +
					'label.ctp-checkbox-label, ' +
					'[name="cf-turnstile-response"], ' +
					'input[name="cf-turnstile-response"]'
				);
				if (challengeCheckbox) {
					// For labels, also try the associated input
					if (challengeCheckbox.tagName === 'LABEL') {
						var forId = challengeCheckbox.getAttribute('for');
						if (forId) {
							var linked = document.getElementById(forId);
							if (linked) dispatchClick(linked);
						}
						var innerInput = challengeCheckbox.querySelector('input');
						if (innerInput) dispatchClick(innerInput);
					}
					if (dispatchClick(challengeCheckbox)) return 'managed_challenge_clicked';
				}

				// Strategy 3: Click buttons inside challenge-stage
				var challengeStage = document.querySelector(
					'#challenge-stage, .challenge-stage, #challenge-running, #challenge-form, #cf-please-wait'
				);
				if (challengeStage) {
					var clickable = challengeStage.querySelector(
						'input[type="submit"], input[type="button"], button, .btn, [role="button"], .ctp-button'
					);
					if (clickable && dispatchClick(clickable)) return 'challenge_button_clicked';
					// Some CF builds need a click on the stage container itself
					if (dispatchClick(challengeStage)) return 'challenge_stage_clicked';
				}

				// Strategy 4: reCAPTCHA / hCaptcha style checkboxes
				var captchaIframes = document.querySelectorAll(
					'iframe[src*="recaptcha"], iframe[src*="hcaptcha"], ' +
					'iframe[title*="reCAPTCHA"], iframe[title*="hCaptcha"]'
				);
				for (var j = 0; j < captchaIframes.length; j++) {
					try {
						var rcDoc = captchaIframes[j].contentDocument ||
							(captchaIframes[j].contentWindow && captchaIframes[j].contentWindow.document);
						if (rcDoc) {
							var rcCheckbox = rcDoc.querySelector(
								'.recaptcha-checkbox-border, .recaptcha-checkbox, #recaptcha-anchor, #checkbox'
							);
							if (rcCheckbox && dispatchClick(rcCheckbox)) return 'recaptcha_checkbox_clicked';
						}
					} catch (e) {
						if (dispatchClick(captchaIframes[j])) return 'recaptcha_iframe_clicked';
					}
				}

				// Strategy 5: Auto-submit challenge forms
				var forms = document.querySelectorAll(
					'form#challenge-form, form.challenge-form, form[action*="challenge"], ' +
					'form[action*="__cf_chl"], form[action*="cdn-cgi/challenge"]'
				);
				for (var k = 0; k < forms.length; k++) {
					var submitBtn = forms[k].querySelector(
						'input[type="submit"], button[type="submit"], button'
					);
					if (submitBtn && dispatchClick(submitBtn)) return 'form_submitted';
					try {
						if (typeof forms[k].submit === 'function') {
							forms[k].submit();
							return 'form_submit_called';
						}
					} catch (e) { /* ignore */ }
				}

				// Strategy 6: Visible Verify buttons (no jQuery :contains — invalid in querySelector)
				var candidates = document.querySelectorAll(
					'input[type="button"], input[type="submit"], button, .verify-button, #verify-button, .ctp-button'
				);
				for (var m = 0; m < candidates.length; m++) {
					var el = candidates[m];
					if (el.offsetParent === null) continue; // not visible
					var text = ((el.value || '') + ' ' + (el.textContent || '')).toLowerCase();
					if (text.indexOf('verify') !== -1 || text.indexOf('continue') !== -1) {
						if (dispatchClick(el)) return 'verify_button_clicked';
					}
				}

				return 'no_challenge_found';
			} catch (e) {
				return 'error: ' + (e && e.message ? e.message : String(e));
			}
		})();
	""".trimIndent()

	/**
	 * Continuous retry loop for late-mounted Turnstile widgets.
	 * Idempotent: re-injection is a no-op if the loop is already running.
	 * Stops itself after [MAX_LOOPS] iterations (~30s at 1.5s interval).
	 */
	val CONTINUOUS_SOLVE_SCRIPT: String = """
		(function() {
			if (window.__kotatsuCaptchaLoop) return 'already_running';
			window.__kotatsuCaptchaLoop = true;
			var loops = 0;
			var MAX_LOOPS = 20;
			var INTERVAL_MS = 1500;

			function stillChallenged() {
				try {
					return !!(
						document.querySelector('#challenge-stage') ||
						document.querySelector('.challenge-stage') ||
						document.querySelector('#challenge-form') ||
						document.querySelector('#challenge-running') ||
						document.querySelector('#cf-challenge') ||
						document.querySelector('#cf-please-wait') ||
						document.querySelector('div.cf-turnstile') ||
						document.querySelector('div#turnstile-wrapper') ||
						document.querySelector('div.cf-turnstile-wrapper') ||
						document.querySelector('iframe[src*="challenges.cloudflare.com"]') ||
						document.querySelector('iframe[src*="turnstile"]') ||
						document.querySelector('#challenge-error-title') ||
						document.querySelector('.ctp-checkbox-label') ||
						document.querySelector('[data-turnstile-sitekey]') ||
						document.querySelector('input[name="cf-turnstile-response"]') ||
						(document.title && (
							document.title.toLowerCase().indexOf('just a moment') !== -1 ||
							document.title.toLowerCase().indexOf('attention required') !== -1
						))
					);
				} catch (e) {
					return false;
				}
			}

			function tick() {
				loops++;
				if (loops > MAX_LOOPS || !stillChallenged()) {
					window.__kotatsuCaptchaLoop = false;
					return;
				}
				try {
					var hosts = document.querySelectorAll(
						'iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"], ' +
						'div.cf-turnstile, div#turnstile-wrapper, div.cf-turnstile-wrapper, ' +
						'.ctp-checkbox-label, ' +
						'#challenge-stage input[type="checkbox"], #challenge-stage .ctp-checkbox-label, ' +
						'[data-turnstile-sitekey], [data-sitekey]'
					);
					for (var i = 0; i < hosts.length; i++) {
						try { hosts[i].click(); } catch (e) {}
						try {
							hosts[i].dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window }));
						} catch (e2) {}
					}
				} catch (e) {}
				setTimeout(tick, INTERVAL_MS);
			}

			setTimeout(tick, 800);
			return 'loop_started';
		})();
	""".trimIndent()

	/**
	 * Lightweight check: is the current page a CloudFlare (or similar) challenge page?
	 * Returns `"true"` or `"false"` as a string.
	 */
	val DETECT_CHALLENGE_SCRIPT: String = """
		(function() {
			try {
				var title = (document.title || '').toLowerCase();
				var hasChallenge = !!(
					document.querySelector('#challenge-stage') ||
					document.querySelector('.challenge-stage') ||
					document.querySelector('#challenge-form') ||
					document.querySelector('#challenge-running') ||
					document.querySelector('#cf-challenge') ||
					document.querySelector('#cf-please-wait') ||
					document.querySelector('div#turnstile-wrapper') ||
					document.querySelector('div.cf-turnstile') ||
					document.querySelector('div[id*="cf-turnstile"]') ||
					document.querySelector('div.cf-turnstile-wrapper') ||
					document.querySelector('iframe[src*="challenges.cloudflare.com"]') ||
					document.querySelector('iframe[src*="turnstile"]') ||
					document.querySelector('script[src*="challenges.cloudflare.com"]') ||
					document.querySelector('script[src*="turnstile"]') ||
					document.querySelector('#challenge-error-title') ||
					document.querySelector('#challenge-error-text') ||
					document.querySelector('.ctp-checkbox-label') ||
					document.querySelector('form[action*="__cf_chl"]') ||
					document.querySelector('form[action*="cdn-cgi/challenge"]') ||
					document.querySelector('input[name="cf-turnstile-response"]') ||
					document.querySelector('[data-turnstile-sitekey]') ||
					document.querySelector('div[data-sitekey]') ||
					title.indexOf('just a moment') !== -1 ||
					title.indexOf('attention required') !== -1 ||
					title.indexOf('cloudflare') !== -1 ||
					title.indexOf('checking your browser') !== -1 ||
					title.indexOf('please wait') !== -1
				);
				return hasChallenge ? 'true' : 'false';
			} catch (e) {
				return 'false';
			}
		})();
	""".trimIndent()

	/**
	 * Extracts the screen-space DP coordinates of the Turnstile / challenge checkbox
	 * so native Android MotionEvents (hardware touch) can be dispatched with isTrusted=true.
	 */
	val GET_WIDGET_COORDINATES_SCRIPT: String = """
		(function() {
			try {
				var selectors = [
					'iframe[src*="challenges.cloudflare.com"]',
					'iframe[src*="turnstile"]',
					'iframe[title*="Cloudflare"]',
					'iframe[title*="Widget containing a Cloudflare"]',
					'div.cf-turnstile',
					'div#turnstile-wrapper',
					'div.cf-turnstile-wrapper',
					'[data-turnstile-sitekey]',
					'#challenge-stage input[type="checkbox"]',
					'.ctp-checkbox-label',
					'#challenge-stage'
				];
				for (var i = 0; i < selectors.length; i++) {
					var el = document.querySelector(selectors[i]);
					if (el) {
						var rect = el.getBoundingClientRect();
						if (rect.width > 0 && rect.height > 0) {
							// Checkbox is ~28px from left edge and vertically centered in Turnstile
							var x = rect.left + Math.min(28, rect.width / 2);
							var y = rect.top + (rect.height / 2);
							return x + ',' + y;
						}
					}
				}
				return null;
			} catch (e) {
				return null;
			}
		})();
	""".trimIndent()
}

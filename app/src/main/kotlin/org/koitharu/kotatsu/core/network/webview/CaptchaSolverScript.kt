package org.koitharu.kotatsu.core.network.webview

/**
 * JavaScript code injected into the WebView to automatically solve
 * CloudFlare JS challenges (Turnstile, Managed Challenge, and generic checkbox challenges).
 *
 * Notes on modern CloudFlare:
 * - Many Managed Challenges complete purely via JS fingerprinting (no click needed).
 * - Turnstile widgets are cross-origin iframes; contentDocument is usually inaccessible.
 * - We therefore combine: synthetic pointer events on widgets, label/checkbox clicks,
 *   shadow-DOM probing, form submits, and a continuous retry loop for late-mounted widgets.
 */
internal object CaptchaSolverScript {

	/**
	 * One-shot auto-solve pass. Returns a string status for debugging.
	 */
	val SOLVE_SCRIPT: String = """
		(function() {
			function dispatchClick(el) {
				if (!el) return false;
				try {
					var opts = { bubbles: true, cancelable: true, view: window };
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
					'div.cf-turnstile, div#turnstile-wrapper, div[id*="cf-turnstile"]'
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
					'[name="cf-turnstile-response"]'
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
					'form#challenge-form, form.challenge-form, form[action*="challenge"], form[action*="__cf_chl"]'
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
						document.querySelector('iframe[src*="challenges.cloudflare.com"]') ||
						document.querySelector('iframe[src*="turnstile"]') ||
						document.querySelector('#challenge-error-title') ||
						document.querySelector('.ctp-checkbox-label') ||
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
					// Re-run the one-shot solver body inline to avoid depending on prior eval state
					var s = document.createElement('script');
					// Directly invoke click strategies via a minimal subset
					var hosts = document.querySelectorAll(
						'iframe[src*="challenges.cloudflare.com"], iframe[src*="turnstile"], ' +
						'div.cf-turnstile, div#turnstile-wrapper, .ctp-checkbox-label, ' +
						'#challenge-stage input[type="checkbox"], #challenge-stage .ctp-checkbox-label'
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
					document.querySelector('iframe[src*="challenges.cloudflare.com"]') ||
					document.querySelector('iframe[src*="turnstile"]') ||
					document.querySelector('script[src*="challenges.cloudflare.com"]') ||
					document.querySelector('script[src*="turnstile"]') ||
					document.querySelector('#challenge-error-title') ||
					document.querySelector('#challenge-error-text') ||
					document.querySelector('.ctp-checkbox-label') ||
					document.querySelector('form[action*="__cf_chl"]') ||
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
}

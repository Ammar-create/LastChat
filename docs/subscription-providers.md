# Account and OpenCode providers

All changes are on `lastchat_dev`. The native UI retains LastChat's surface colors,
28dp cards, standard field shapes and pill actions. Account providers show their
logo only in the toolbar. Account state, connection options and usage are grouped
without nested decorative cards.

## ChatGPT

New sign-ins follow RikkaHub 3.1.0's Codex account flow, cross-checked with OpenAI's
native client source. They use the registered public client, PKCE, state, a nonce,
RS256 identity verification and the exact `http://127.0.0.1:1455/auth/callback`
redirect. The loopback listener runs only on the device and a foreground service
keeps the transaction alive while the browser is open. Activity recreation does
not cancel it. Browser attempts expire after five minutes; device-code sign-in is
available as an alternative and expires after fifteen minutes.

`Continue with ChatGPT` uses the browser. `Use a sign-in code instead` uses
OpenAI's device authorization endpoints and does not depend on a loopback callback.
Some accounts require enabling device-code authorization in ChatGPT settings.

The account's Codex backend supplies its model catalog and usage windows. Requests
use Responses streaming, `store=false`, account/session headers, developer context,
plain function tools and encrypted reasoning replay. Unsupported sampling/output
fields are removed after custom body settings. A completed stream is required;
errors and interrupted streams cannot be reported as success.

A browser callback confirms only receipt of the authorization result. The local
page explicitly says LastChat still needs to check the connection. An encrypted,
short-lived checkpoint retains the code and PKCE verifier before exchange, and
then the received token response before identity-key retrieval. A temporary DNS
failure after the callback can therefore resume without reusing an already consumed
code. Terminal exchange errors discard the code. Credentials are activated only
after signature, issuer, audience, expiry, nonce and account claims are checked.

Existing direct-plan sessions remain readable and refresh using their original
resource/client contract. Reconnecting upgrades the provider to the Codex flow;
the two kinds of credentials are never sent to each other's inference endpoints.

Tokens, pending exchanges and rotating refresh credentials live in encrypted device
storage through `SecretKeyManager`, outside settings, exports and backups. Refresh
is serialized and a bounded in-flight token rotation finishes persisting even if
the initiating chat is cancelled. Sign-out removes credentials and pending sign-in;
provider deletion also removes stored registration information.

### DNS and ad blockers

The reported screenshot shows `UnknownHostException` for `auth.openai.com` after a
successful browser callback. The browser and app can use different resolvers.
Changing an OAuth URL does not repair that network failure.

Connection options expose **Secure DNS fallback**, disabled by default. When enabled,
the account HTTP client still tries system DNS first. Only failed lookups for the
allowlisted OpenAI/xAI account and inference hosts can use Cloudflare DNS over HTTPS.
Other destinations, including private/local names and the rest of the app's traffic,
keep their existing DNS behavior. HTTPS certificate/hostname verification stays on.
The UI explains this before the user enables it; blocking can instead be adjusted
in the user's DNS/ad-blocker configuration.

## Grok

Grok uses its public native device-code flow. Chat generation uses xAI Responses;
model discovery merges the CLI `models-v2` catalog and the public API catalog.
Subscription usage comes from CLI billing, supporting weekly and unified monthly
accounts. CLI requests carry the required product-channel header; generation carries
the conversation ID. The request body uses system context and encrypted reasoning
replay, omits unsupported summaries, and adapts Grok's effort levels. The same
encrypted storage, cancellation and opt-in DNS controls apply. Account/model
eligibility is determined by xAI; there is no separately billed API-key fallback.

## OpenCode Zen and Go

Existing `/zen/v1` and `/zen/go/v1` provider entries are recognized and routed to
OpenCode's current `/inference` API. Provider IDs, API keys and stored model choices
are preserved. The catalog adds a separate Zen preset and keeps the original
OpenCode preset ID for Go.

The API is selected per model: GPT/Grok/Muse use Responses, Claude and most Qwen
models use Messages, Gemini uses its native API, and other open models use Chat
Completions. Go's MiniMax M3 and Qwen3.8 Max use Messages while Zen's use Chat.
Native adapters retain their own key selection, with authentication normalized to
OpenCode's Bearer scheme. All generation paths preserve a conversation's
`x-opencode-session` and identify the client as LastChat. Non-stream callers collect
the same streaming path instead of trying to decode SSE as one JSON document.

Go usage is fetched from `/inference/go/v1/usage` with the configured key. The UI
shows the provider's rolling, weekly and monthly percentages and reset times, and
links to Console and current model rates. Unrecognized schemas/errors never turn
into a misleading zero usage figure. Zen exposes its Console link when quota data
is unavailable. Quota reporting does not invent billing balances or prices.

Keyless free-tier requests may be restricted to OpenCode's own client. Requests
retain LastChat's identity and show the provider's restriction. Account limits,
model permissions and regional restrictions remain server decisions.

Model-specific request normalization also matters inside an API family. OpenCode
Grok requests omit xAI's unsupported reasoning summary and use the supported effort
values. Current Claude generations use adaptive thinking and effort instead of manual
budgets, omit unsupported sampling fields and avoid assistant prefill. Legacy Claude
requests retain valid manual thinking budgets below their output cap. These changes
are scoped to the OpenCode transport; normal Anthropic provider settings are preserved.

## Verification and limits

Anonymous checks on 2026-10-19 confirmed that the Codex browser request reaches
OpenAI login (HTTP 200), device authorization starts (HTTP 200), and current OpenCode
Zen/Go model lists return HTTP 200. Go usage without a key returns the expected
HTTP 401. A free inference probe returned a structured HTTP 403 explaining its
client restriction. These checks do not establish a particular account's paid
model access or prove end-to-end subscription inference.

Regression coverage includes real loopback callback/state validation; recovery after
a consumed code and failed identity-key DNS lookup; device sign-in/JSON refresh;
legacy session refresh; opt-in DNS isolation; all four OpenCode wire formats and Go
routing exceptions; consistent session/auth headers; SSE error bodies; quota parsing;
Codex request constraints and encrypted reasoning replay; and bundled catalog resolution.
No phone or emulator is attached to this workspace; final account authorization and
visual behavior require device testing.

## Sources

- [RikkaHub 3.1.0](https://github.com/rikkahub/rikkahub/releases/tag/3.1.0)
- [RikkaHub ChatGPT account contract](https://github.com/rikkahub/rikkahub/blob/e5bb70730b4b2cb06c2e049168a1ecc89d41f536/app/src/main/java/me/rerere/rikkahub/data/ai/account/ChatGPTAuth.kt)
- [OpenAI native browser login](https://github.com/openai/codex/blob/main/codex-rs/login/src/server.rs)
- [OpenAI native device login](https://github.com/openai/codex/blob/main/codex-rs/login/src/device_code_auth.rs)
- [OpenCode inference API](https://opencode.ai/v2/docs/console/api/inference/)
- [OpenCode Go endpoints and limits](https://opencode.ai/v2/docs/console/go/)
- [OpenCode legacy-route migration](https://github.com/anomalyco/opencode/blob/6bac3b9ad8a3bed5ac01b8f6a3c30850a4d0468/packages/console/app/src/lib/inference-proxy.ts)
- [OpenCode quota response contract](https://github.com/anomalyco/opencode/blob/5caf1f9f6f5f/packages/console/app/src/routes/zen/go/v1/usage.ts)
- [DeepSeek current models and vision support](https://api-docs.deepseek.com/quick_start/pricing/)
- [GPT-6.1 Sol capabilities](https://developers.openai.com/api/docs/models/gpt-6.1-sol)
- [Gemini 4 Argon capabilities](https://ai.google.dev/gemini-api/docs/models/gemini-4-argon)

- [RikkaHub Grok contract](https://github.com/rikkahub/rikkahub/blob/e5bb70730b4b2cb06c2e049168a1ecc89d41f536/ai/src/main/java/me/rerere/ai/provider/providers/grok/GrokProvider.kt)

- [Claude Haiku 5.5 migration requirements](https://platform.claude.com/docs/en/models/haiku-5-5/whats-new-haiku-5-5)

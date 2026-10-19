# AI Blocker Management Worker

This Worker provides:

- anonymous device heartbeat / registration counts per 4-6 digit class code
- per-class blocking ON/OFF
- per-class registration reset epoch
- shared AI block/allow lists

## One-time Cloudflare setup

1. Create or sign in to a Cloudflare account.
2. Workers & Pages -> Create application -> Import a repository.
3. Choose `jaehun0478-rgb/ai-mode-blocker`.
4. Set the project root directory to `management-worker`.
5. Deploy.
6. In Cloudflare: Storage & Databases -> Workers KV -> Create instance.
   Suggested name: `ai-blocker-data`.
7. Open the deployed Worker -> Settings -> Bindings -> Add -> KV Namespace.
8. Variable name MUST be `DATA`.
9. Select the KV instance and deploy the binding.
10. Open `https://<worker>.workers.dev/health`.
    Expected: `{"ok":true,"service":"ai-blocker-management"}`.

After this, put the Worker base URL (without a trailing slash) into
`remote-policy.json` as `managementApiBase`.

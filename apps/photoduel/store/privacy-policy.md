# PhotoDuel privacy policy — DRAFT for counsel review

> **Draft, not published.** This text describes the development build. It is not legal advice and must be reviewed before any public link is created. Placeholders in square brackets must be completed by the operator.

**Operator:** [legal name of the agency and registered address — PENDING]
**Contact for privacy requests:** [privacy email address — PENDING, not yet provisioned]
**Web deletion request page:** [URL — PENDING; no domain exists yet]

## 1. What PhotoDuel is

PhotoDuel lets small groups of friends run private photo challenges. Rooms are private. Only members of a room can see its photos, and only after voting opens.

## 2. Information we collect

| Data | Why | Where it lives | Who can see it |
|---|---|---|---|
| Display name you choose (1–24 characters; not your real name is required) | Shown to your room members | Server | Members of your rooms |
| A random session token | Keeps you signed in | Your device, encrypted with an Android Keystore key; only a hash on the server | You |
| A one-time recovery code | Lets you sign in again on a new device | Shown once; the server keeps only a hash | You |
| Photos you choose to submit | The challenge | Server, after re-encoding | Members of the room during voting and results; you while you own it |
| Thumbnails of your photos | Faster gallery | Server | Same as photos |
| Votes | Results | Server; your identity is removed from the vote after the challenge ends (kept only as a count) | Results show counts, never who voted for whom |
| Reports you file, and any details you add | Safety review | Server, 180 days | The operator's moderation team only; never shown to the reported person |
| Blocks you set | Hides the other person's content | Server | You |
| Network request metadata and an IP hash | Rate limiting and abuse prevention | Server; the IP is stored only as a keyed hash for short periods | Operator, for security |

We do **not** collect: real names, email addresses, phone numbers, contacts, location, advertising identifiers, or your photo library. The app does not read photos you do not choose. PhotoDuel does not contain advertising or third-party analytics SDKs in this build.

## 3. Photo metadata

Before upload, the app removes EXIF and location metadata. The server re-encodes every photo and removes all metadata a second time. Photos are never published on a public web address; they are served only to authorized members, after an authorization check on each request.

## 4. How long we keep data

See [data-deletion-and-retention.md](data-deletion-and-retention.md). In summary: photos of a finished challenge are removed 30 days after it ends; deleted accounts are removed immediately; reports are kept for 180 days after resolution.

## 5. Your choices

- Rename yourself, leave a room, block someone, report content, or delete your own photo at the permitted times.
- Delete your account inside the app (Profile, Delete account). This removes your sign-in, your photos and your membership, and it replaces your name in history with "Deleted player".
- Request deletion on the web when you no longer have the app: [web deletion page — PENDING]. Google Play requires this link for apps that allow sign-up.

## 6. Sharing

We do not sell data or share it with advertisers. The service runs on infrastructure operated by [hosting provider — PENDING]. Data is processed by the operator and its service provider only.

## 7. Children

PhotoDuel is intended for people aged 13 and over and is not directed to children under 13. If you believe a child has provided personal information, contact [privacy contact — PENDING] and we will delete it.

## 8. Security

Passwords do not exist in this design. Tokens and recovery codes are stored as hashes or encrypted. Transport requires HTTPS in release builds. No system is perfectly secure; please use a recovery code and keep your device locked.

## 9. Changes

We will publish changes here and update the version date. Material changes will be announced in the app before they take effect.

**Version date:** [PENDING — set on publication]

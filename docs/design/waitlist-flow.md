# Waitlist Flow

The waitlist flow is the lightweight conversion path for `parkio.dev`.

## Flow

Visitor

|
v

Landing page

|
v

Email

|
v

Consent

|
v

Beta application option

|
v

Confirmation

|
v

Future invite

## Entry Points

- Header CTA
- Hero CTA
- Hosted Beta CTA
- Footer CTA

All entry points should use the same promise:

> Join the waitlist for future hosted-beta invites.

## Step 1: Email

Required field:

- Email address

Optional fields:

- First name
- City or general area

Copy:

> Join the Parkio beta waitlist. Invites, supported areas, and timing are TBD.

Validation:

- Email is required.
- Email must be valid.
- Avoid aggressive validation before submission.

## Step 2: Consent

Required consent:

- Agreement to receive Parkio beta updates.

Recommended consent copy:

> I agree to receive Parkio beta updates and understand that hosted-beta access is not guaranteed.

Optional consent:

- Product research contact permission.

Recommended copy:

> Parkio may contact me for optional product feedback.

Privacy note:

> Parkio will use this information for beta communication and planning. Public privacy policy URL: TBD.

## Step 3: Beta Application Option

After email and consent, offer a deeper beta application:

- Continue to beta application
- Finish with waitlist only

Rationale:

- Keep the primary waitlist simple.
- Let motivated testers provide richer context.

## Step 4: Confirmation

Confirmation headline:

> You're on the Parkio waitlist.

Confirmation copy:

> Thanks for your interest. Parkio is preparing for hosted beta. Invite timing, supported areas, and cohort size are TBD.

Recommended next links:

- Follow development
- View technical documentation
- Read FAQ

## Future Invite

Invite status should be honest:

- Submitted
- Reviewing
- Invited
- Not selected yet

Avoid:

- "Approved" unless there is a real review process.
- "Guaranteed access."
- "Your city is supported" unless confirmed.

## Data Notes

Minimum waitlist data:

- Email
- Consent timestamp
- Optional city or general area
- Optional product research permission

Do not request precise location in the waitlist form.

## Error States

Required:

- Invalid email
- Missing consent
- Submission failed
- Duplicate email, if detectable

Tone:

- Calm and specific.
- Do not blame the user.

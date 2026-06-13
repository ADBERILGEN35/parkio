import { z } from 'zod';

/**
 * User-analytics lookup form. The backend takes no other parameters anywhere in
 * analytics — no date ranges or metric filters exist to validate.
 */
export const userAnalyticsLookupSchema = z.object({
  userId: z.string().trim().uuid('Enter a valid user id (UUID)'),
});

export type UserAnalyticsLookupValues = z.infer<typeof userAnalyticsLookupSchema>;

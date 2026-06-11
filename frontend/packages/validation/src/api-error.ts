import { z } from 'zod';

export const fieldErrorSchema = z.object({
  field: z.string(),
  message: z.string(),
});

export const apiErrorSchema = z.object({
  code: z.string(),
  message: z.string(),
  traceId: z.string(),
  timestamp: z.string(),
  fieldErrors: z.array(fieldErrorSchema).optional(),
});

export type ApiErrorInput = z.infer<typeof apiErrorSchema>;

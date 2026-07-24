import { z } from 'zod';

export const uuidSchema = z.string().uuid();

export const instantSchema = z.string().datetime({ offset: true });

const localDatePattern = /^(\d{4})-(\d{2})-(\d{2})$/;

function isCalendarDate(value: string): boolean {
  const match = localDatePattern.exec(value);
  if (!match) return false;

  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  if (month < 1 || month > 12) return false;

  const leapYear = year % 4 === 0 && (year % 100 !== 0 || year % 400 === 0);
  const daysInMonth = [31, leapYear ? 29 : 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31];
  return day >= 1 && day <= daysInMonth[month - 1];
}

export const localDateSchema = z.string().regex(localDatePattern).refine(isCalendarDate);

export const localTimeSchema = z
  .string()
  .regex(/^([01]\d|2[0-3]):[0-5]\d(?::[0-5]\d(?:\.\d{1,9})?)?$/);

export const finiteNumberSchema = z.number().finite();

export const integerSchema = z.number().int();

export const nonNegativeIntegerSchema = integerSchema.nonnegative();

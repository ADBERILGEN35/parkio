import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { API_BASE, apiErrorBody, server } from '@/test/server';
import { LandingPage } from './LandingPage';

function renderLanding() {
  return render(
    <MemoryRouter>
      <LandingPage />
    </MemoryRouter>,
  );
}

describe('LandingPage', () => {
  it('renders the canonical positioning and honest stage', () => {
    renderLanding();

    expect(
      screen.getByRole('heading', { name: 'Parking intelligence powered by real drivers.' }),
    ).toBeInTheDocument();
    expect(
      screen.getAllByText(
        /helps drivers find, share, verify, and manage real-world parking availability/i,
      ).length,
    ).toBeGreaterThan(0);
    expect(screen.getAllByText(/public production service is not live yet/i).length).toBeGreaterThan(0);
    for (const link of screen.getAllByRole('link', { name: /view technical documentation/i })) {
      expect(link).toHaveAttribute('href', expect.stringContaining('/docs/startup'));
    }
  });

  it('validates waitlist email and consent before submission', async () => {
    const user = userEvent.setup();
    renderLanding();

    await user.click(screen.getByRole('button', { name: /join beta waitlist/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Enter a valid email address.');

    await user.type(screen.getByLabelText(/^email/i), 'driver@parkio.dev');
    await user.click(screen.getByRole('button', { name: /join beta waitlist/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Consent is required to receive beta updates.',
    );
  });

  it('submits the real waitlist flow successfully', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API_BASE}/waitlist`, () => HttpResponse.json({ status: 'accepted' }, { status: 202 })),
    );
    renderLanding();

    await user.type(screen.getByLabelText(/^email/i), 'driver@parkio.dev');
    await user.selectOptions(screen.getByLabelText(/role/i), 'tester');
    await user.type(screen.getByLabelText(/city or general area/i), 'Izmir');
    await user.click(
      screen.getByLabelText(/I agree to receive Parkio beta updates/i),
    );
    await user.click(screen.getByRole('button', { name: /join beta waitlist/i }));

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: 'Your Parkio beta interest is ready.' })).toBeInTheDocument(),
    );
  });

  it('shows an error when the waitlist API fails', async () => {
    const user = userEvent.setup();
    server.use(
      http.post(`${API_BASE}/waitlist`, () =>
        HttpResponse.json(apiErrorBody('RATE_LIMITED', 'Too many waitlist submissions.'), { status: 429 }),
      ),
    );
    renderLanding();

    await user.type(screen.getByLabelText(/^email/i), 'driver@parkio.dev');
    await user.click(screen.getByLabelText(/I agree to receive Parkio beta updates/i));
    await user.click(screen.getByRole('button', { name: /join beta waitlist/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'The waitlist could not submit. Try again in a moment.',
    );
  });

  it('keeps waitlist intake to minimal optional planning fields', () => {
    renderLanding();

    expect(screen.queryByLabelText(/first name/i)).not.toBeInTheDocument();
    expect(screen.getByLabelText(/role/i)).toBeInTheDocument();
    expect(screen.getByRole('option', { name: 'Driver' })).toHaveValue('driver');
    expect(screen.getByRole('option', { name: 'Tester' })).toHaveValue('tester');
    expect(screen.getByRole('option', { name: 'Partner' })).toHaveValue('partner');
    expect(screen.getByLabelText(/city or general area/i)).toBeInTheDocument();
  });

  it('keeps FAQ answers discoverable', async () => {
    const user = userEvent.setup();
    renderLanding();

    const faq = screen.getByText('Is Parkio live?');
    await user.click(faq);

    const details = faq.closest('details');
    expect(details).toHaveAttribute('open');
    expect(within(details as HTMLElement).getByText(/public production service is not live yet/i)).toBeInTheDocument();
  });
});

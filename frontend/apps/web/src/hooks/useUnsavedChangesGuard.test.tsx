import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, Link, RouterProvider, useNavigate } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { useUnsavedChangesGuard } from './useUnsavedChangesGuard';

function GuardedPage({ when }: { when: boolean }) {
  const blocker = useUnsavedChangesGuard({ when });
  const navigate = useNavigate();
  return (
    <div>
      <h1>Upload</h1>
      <button type="button" onClick={() => navigate('/map')}>
        Go map
      </button>
      <Link to="/my-spots">My spots</Link>
      <ConfirmDialog
        open={blocker.state === 'blocked'}
        title="Leave parking spot sharing?"
        description="You have unsaved changes."
        cancelLabel="Continue sharing"
        confirmLabel="Leave and discard changes"
        onCancel={() => {
          if (blocker.state === 'blocked') blocker.reset();
        }}
        onConfirm={() => {
          if (blocker.state === 'blocked') blocker.proceed();
        }}
      />
    </div>
  );
}

function renderGuard(when: boolean, initialEntries = ['/upload']) {
  const router = createMemoryRouter(
    [
      { path: '/upload', element: <GuardedPage when={when} /> },
      { path: '/map', element: <h1>Map page</h1> },
      { path: '/my-spots', element: <h1>My spots page</h1> },
      { path: '/login', element: <h1>Login page</h1> },
    ],
    { initialEntries },
  );
  return { ...render(<RouterProvider router={router} />), router };
}

describe('useUnsavedChangesGuard', () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('allows navigation when clean', async () => {
    const user = userEvent.setup();
    renderGuard(false);
    await user.click(screen.getByRole('link', { name: 'My spots' }));
    expect(await screen.findByRole('heading', { name: 'My spots page' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('blocks link navigation when dirty and cancels safely', async () => {
    const user = userEvent.setup();
    renderGuard(true);
    await user.click(screen.getByRole('link', { name: 'My spots' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Upload' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Continue sharing' }));
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.getByRole('heading', { name: 'Upload' })).toBeInTheDocument();
  });

  it('proceeds to the intended destination on discard', async () => {
    const user = userEvent.setup();
    renderGuard(true);
    await user.click(screen.getByRole('button', { name: 'Go map' }));
    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Leave and discard changes' }));
    expect(await screen.findByRole('heading', { name: 'Map page' })).toBeInTheDocument();
  });

  it('registers beforeunload only while dirty', () => {
    const add = vi.spyOn(window, 'addEventListener');
    const remove = vi.spyOn(window, 'removeEventListener');
    const { unmount } = render(
      <RouterProvider
        router={createMemoryRouter([{ path: '/', element: <GuardedPage when={true} /> }], {
          initialEntries: ['/'],
        })}
      />,
    );
    expect(add).toHaveBeenCalledWith('beforeunload', expect.any(Function));
    unmount();
    expect(remove).toHaveBeenCalledWith('beforeunload', expect.any(Function));

    // Clean mount must not register
    add.mockClear();
    render(
      <RouterProvider
        router={createMemoryRouter([{ path: '/', element: <GuardedPage when={false} /> }], {
          initialEntries: ['/'],
        })}
      />,
    );
    const beforeUnloadCalls = add.mock.calls.filter((c) => c[0] === 'beforeunload');
    expect(beforeUnloadCalls).toHaveLength(0);
  });

  it('does not block navigation to /login (auth escape)', async () => {
    const user = userEvent.setup();
    function EscapePage() {
      const blocker = useUnsavedChangesGuard({ when: true });
      const navigate = useNavigate();
      return (
        <div>
          <h1>Upload</h1>
          <button type="button" onClick={() => navigate('/login')}>
            Expire
          </button>
          <ConfirmDialog
            open={blocker.state === 'blocked'}
            title="Leave?"
            description="dirty"
            cancelLabel="Stay"
            confirmLabel="Leave"
            onCancel={() => blocker.reset?.()}
            onConfirm={() => blocker.proceed?.()}
          />
        </div>
      );
    }
    const router = createMemoryRouter(
      [
        { path: '/upload', element: <EscapePage /> },
        { path: '/login', element: <h1>Login page</h1> },
      ],
      { initialEntries: ['/upload'] },
    );
    render(<RouterProvider router={router} />);
    await user.click(screen.getByRole('button', { name: 'Expire' }));
    expect(await screen.findByRole('heading', { name: 'Login page' })).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

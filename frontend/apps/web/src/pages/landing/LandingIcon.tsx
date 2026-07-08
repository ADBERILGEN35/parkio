interface LandingIconProps {
  name: string;
  className?: string;
}

const iconPaths: Record<string, string> = {
  add_location_alt: 'M12 2a7 7 0 0 0-7 7c0 5.3 7 13 7 13s7-7.7 7-13a7 7 0 0 0-7-7Zm0 9.5A2.5 2.5 0 1 1 12 6a2.5 2.5 0 0 1 0 5.5Zm-4 6h8M12 14v7',
  arrow_downward: 'M12 4v14m0 0 6-6m-6 6-6-6',
  arrow_forward: 'M5 12h14m0 0-6-6m6 6-6 6',
  check: 'm5 12 4 4L19 6',
  check_circle: 'M21 11.1V12a9 9 0 1 1-5.3-8.2M9 12l2 2 6-7',
  fact_check: 'M4 5h16v14H4zM8 9h1m3 0h4M8 13h1m3 0h4M8 17h1m3 0h4',
  groups: 'M8 11a3 3 0 1 0 0-6 3 3 0 0 0 0 6Zm8 0a3 3 0 1 0 0-6 3 3 0 0 0 0 6ZM3 20a5 5 0 0 1 10 0m-2 0a5 5 0 0 1 10 0',
  hub: 'M12 12 5 7m7 5 7-5m-7 5v8M5 7a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm14 0a2 2 0 1 0 0-4 2 2 0 0 0 0 4Zm-7 15a2 2 0 1 0 0-4 2 2 0 0 0 0 4Z',
  info: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Zm0-11v6m0-10h.01',
  keyboard_return: 'M20 6v6H7m0 0 4-4m-4 4 4 4',
  local_parking: 'M8 21V3h7a5 5 0 0 1 0 10h-3v8H8Zm4-12h3a1 1 0 0 0 0-2h-3v2Z',
  lock: 'M7 10V8a5 5 0 0 1 10 0v2m-9 0h8a2 2 0 0 1 2 2v7H6v-7a2 2 0 0 1 2-2Z',
  map: 'M9 18 3 21V6l6-3 6 3 6-3v15l-6 3-6-3Zm0 0V3m6 18V6',
  monitoring: 'M4 19V5m0 14h16M8 15l3-4 3 2 4-7',
  notifications_active: 'M18 16H6l2-2V9a4 4 0 0 1 8 0v5l2 2Zm-8 3h4M18 6l2-2M4 4l2 2',
  open_in_new: 'M14 4h6v6m0-6-9 9M20 14v6H4V4h6',
  phone_iphone: 'M8 2h8v20H8zM11 18h2',
  progress_activity: 'M12 2a10 10 0 0 1 10 10',
  radio_button_checked: 'M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20Zm0-6a4 4 0 1 0 0-8 4 4 0 0 0 0 8Z',
  search: 'M10.5 18a7.5 7.5 0 1 1 5.3-12.8A7.5 7.5 0 0 1 10.5 18Zm5.5-2 5 5',
  shield: 'M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10Z',
  travel_explore: 'M11 20a9 9 0 1 1 7.8-4.5L22 19l-2 2-3.6-3.2A9 9 0 0 1 11 20Zm-3-9h6m-3-3v6',
  verified: 'M12 2l2.4 2 3.1-.2 1.1 2.9 2.7 1.6-1 3 1 3-2.7 1.6-1.1 2.9-3.1-.2-2.4 2-2.4-2-3.1.2-1.1-2.9-2.7-1.6 1-3-1-3 2.7-1.6 1.1-2.9 3.1.2L12 2Zm-4 10 3 3 6-6',
};

export function LandingIcon({ name, className }: LandingIconProps) {
  const path = iconPaths[name] ?? iconPaths.info;

  return (
    <svg
      aria-hidden="true"
      className={className ? `landing-icon ${className}` : 'landing-icon'}
      fill="none"
      focusable="false"
      stroke="currentColor"
      strokeLinecap="round"
      strokeLinejoin="round"
      strokeWidth="2"
      viewBox="0 0 24 24"
    >
      <path d={path} />
    </svg>
  );
}

import { Component, type ReactNode } from 'react';

type Props = {
  children: ReactNode;
  fallback: ReactNode;
  onError?: () => void;
};

type State = { hasError: boolean };

/**
 * Local boundary for optional detail maps — keeps facility text and open-in-maps
 * available when MapLibre/react-map-gl throws during render.
 */
export class MunicipalMapErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(): void {
    this.props.onError?.();
  }

  render(): ReactNode {
    if (this.state.hasError) {
      return this.props.fallback;
    }
    return this.props.children;
  }
}

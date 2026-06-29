import { Component, type ErrorInfo, type ReactNode } from 'react';
import { View } from 'react-native';
import { lightTheme } from '@/theme';
import { AppText } from '@/components/ui/AppText';
import { Button } from '@/components/ui/Button';

interface Props {
  children: ReactNode;
}

interface State {
  error: Error | null;
}

/**
 * Top-level error boundary. Catches render-time crashes anywhere in the tree and
 * shows a recoverable fallback instead of a white screen. Uses the static
 * `lightTheme` directly because the boundary may render above the ThemeProvider.
 */
export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // M2+: forward to a crash-reporting sink (e.g. Sentry). Console for now.
    if (__DEV__) {
      console.error('[ErrorBoundary]', error, info.componentStack);
    }
  }

  private reset = () => this.setState({ error: null });

  render() {
    if (this.state.error) {
      return (
        <View
          style={{
            flex: 1,
            alignItems: 'center',
            justifyContent: 'center',
            padding: 24,
            gap: 12,
            backgroundColor: lightTheme.colors.background,
          }}
        >
          <AppText variant="heading">Something went wrong</AppText>
          <AppText variant="body" tone="muted" style={{ textAlign: 'center' }}>
            The app hit an unexpected error. You can try again.
          </AppText>
          <Button label="Try again" onPress={this.reset} fullWidth={false} />
        </View>
      );
    }
    return this.props.children;
  }
}

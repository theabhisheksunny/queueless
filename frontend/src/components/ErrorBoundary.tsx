import { Component, type ErrorInfo, type ReactNode } from 'react';
import { AlertCircle, RefreshCw, Home } from 'lucide-react';

interface Props {
  children: ReactNode;
  fallback?: ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
  errorReference: string;
  retryCount: number;
}

export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = {
      hasError: false,
      error: null,
      errorReference: '',
      retryCount: 0,
    };
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    const errorReference = `ERR-${Math.random().toString(36).substring(2, 8).toUpperCase()}`;
    return {
      hasError: true,
      error,
      errorReference,
    };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    // Sanitize error info to ensure no sensitive credentials or keys leak into logs
    const safeMessage = error.message ? error.message.replace(/([0-9]{6}|Bearer\s+[A-Za-z0-9-_.]+)/g, '[REDACTED]') : 'Unknown rendering error';

    console.error(`[QueueLess ErrorBoundary Ref: ${this.state.errorReference}]`, {
      message: safeMessage,
      componentStack: errorInfo.componentStack,
    });
  }

  handleRetry = (): void => {
    if (this.state.retryCount >= 3) {
      // Prevent infinite rendering retry loops: force a clean browser reload on excessive retries
      window.location.reload();
      return;
    }
    this.setState((prev) => ({
      hasError: false,
      error: null,
      retryCount: prev.retryCount + 1,
    }));
  };

  handleGoDashboard = (): void => {
    window.location.href = '/';
  };

  render(): ReactNode {
    if (this.state.hasError) {
      if (this.props.fallback) {
        return this.props.fallback;
      }

      return (
        <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-4 selection:bg-brand-500 selection:text-white">
          <div className="max-w-md w-full text-center bg-white rounded-3xl p-8 border border-slate-200/80 shadow-xl shadow-slate-200/50">
            {/* Visual Icon */}
            <div className="w-16 h-16 rounded-2xl bg-rose-50 border border-rose-100 flex items-center justify-center mx-auto mb-5 text-rose-600 shadow-md shadow-rose-500/10">
              <AlertCircle className="w-8 h-8" />
            </div>

            {/* Error Code Badge */}
            <div className="inline-flex items-center px-3 py-1 rounded-full text-xs font-bold uppercase tracking-wider bg-rose-100/80 text-rose-700 mb-4">
              Rendering Notice • {this.state.errorReference}
            </div>

            {/* Heading */}
            <h1 className="text-2xl font-extrabold text-slate-900 tracking-tight mb-2">
              Something went wrong
            </h1>

            {/* User-friendly explanation */}
            <p className="text-slate-600 text-sm leading-relaxed mb-6">
              We encountered an unexpected display issue while loading this view. Your queue data and account status remain secure.
            </p>

            {/* Actions */}
            <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
              <button
                onClick={this.handleRetry}
                className="w-full sm:w-auto px-5 py-3 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-semibold text-sm shadow-md shadow-brand-500/20 transition-all flex items-center justify-center gap-2"
              >
                <RefreshCw className="w-4 h-4" />
                <span>{this.state.retryCount >= 2 ? 'Reload Application' : 'Try Again'}</span>
              </button>

              <button
                onClick={this.handleGoDashboard}
                className="w-full sm:w-auto px-5 py-3 rounded-2xl border border-slate-200 bg-white hover:bg-slate-50 text-slate-700 font-semibold text-sm transition-all flex items-center justify-center gap-2 shadow-sm"
              >
                <Home className="w-4 h-4" />
                <span>Go to Dashboard</span>
              </button>
            </div>

            {/* Footer */}
            <div className="mt-8 text-xs text-slate-400 font-medium">
              QueueLess • Digital Token & Queue Operating System
            </div>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

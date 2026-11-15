import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import { api, type CustomerPositionData } from '../api/client';
import { useLanguage } from '../context/LanguageContext';
import { StatusBadge } from '../components/StatusBadge';
import { ConfirmModal } from '../components/ConfirmModal';
import {
  QrCode,
  Volume2,
  AlertTriangle,
  RotateCcw,
  LogOut,
  Copy,
  Check,
  Globe,
  CheckCircle2,
} from 'lucide-react';

// Web Audio API chime synthesizer for when token is CALLED
function playNotificationChime() {
  try {
    const ctx = new (window.AudioContext || (window as any).webkitAudioContext)();
    const osc = ctx.createOscillator();
    const gain = ctx.createGain();

    osc.type = 'sine';
    // Gentle cheerful two-tone chime
    osc.frequency.setValueAtTime(587.33, ctx.currentTime); // D5
    osc.frequency.setValueAtTime(880.0, ctx.currentTime + 0.15); // A5

    gain.gain.setValueAtTime(0.2, ctx.currentTime);
    gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.8);

    osc.connect(gain);
    gain.connect(ctx.destination);

    osc.start();
    osc.stop(ctx.currentTime + 0.8);
  } catch (e) {
    // Audio context may require prior user interaction
  }
}

export const CustomerTrackPage: React.FC = () => {
  const { trackingToken } = useParams<{ trackingToken: string }>();
  const { language, toggleLanguage, t } = useLanguage();

  const [positionInfo, setPositionInfo] = useState<CustomerPositionData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [confirmLeaveOpen, setConfirmLeaveOpen] = useState(false);
  const [sseConnected, setSseConnected] = useState(false);

  const prevStatusRef = useRef<string | null>(null);

  const fetchPosition = useCallback(async () => {
    if (!trackingToken) return;
    try {
      const data = await api.trackPosition(trackingToken);
      setPositionInfo(data);
      setError(null);

      // Check if status transitioned to CALLED or SERVING
      if (
        prevStatusRef.current &&
        prevStatusRef.current !== data.status &&
        (data.status === 'CALLED' || data.status === 'SERVING')
      ) {
        playNotificationChime();
        if ('vibrate' in navigator) {
          navigator.vibrate([200, 100, 200]);
        }
      }
      prevStatusRef.current = data.status;
    } catch (err: any) {
      setError(err.message || 'Token not found or expired');
    } finally {
      setIsLoading(false);
    }
  }, [trackingToken]);

  // Connect SSE for customer live updates
  useEffect(() => {
    fetchPosition();

    if (!trackingToken) return;

    const sseUrl = api.getCustomerSseUrl(trackingToken);
    const eventSource = new EventSource(sseUrl);

    eventSource.onopen = () => {
      setSseConnected(true);
    };

    eventSource.addEventListener('POSITION_UPDATED', (e: MessageEvent) => {
      try {
        const updatedData: CustomerPositionData = JSON.parse(e.data);
        setPositionInfo(updatedData);

        if (
          prevStatusRef.current &&
          prevStatusRef.current !== updatedData.status &&
          (updatedData.status === 'CALLED' || updatedData.status === 'SERVING')
        ) {
          playNotificationChime();
          if ('vibrate' in navigator) {
            navigator.vibrate([200, 100, 200]);
          }
        }
        prevStatusRef.current = updatedData.status;
      } catch (err) {
        console.error('Failed to parse customer SSE data', err);
      }
    });

    eventSource.onerror = () => {
      setSseConnected(false);
    };

    return () => {
      eventSource.close();
    };
  }, [trackingToken, fetchPosition]);

  const handleLeaveQueue = async () => {
    if (!trackingToken) return;
    try {
      const updated = await api.leaveQueue(trackingToken);
      setPositionInfo(updated);
    } catch (err: any) {
      alert(err.message || 'Failed to leave queue');
    }
  };

  const handleRejoinQueue = async () => {
    if (!trackingToken) return;
    try {
      const updated = await api.rejoinQueue(trackingToken);
      setPositionInfo(updated);
    } catch (err: any) {
      alert(err.message || 'Failed to rejoin queue');
    }
  };

  const handleCopyLink = () => {
    navigator.clipboard.writeText(window.location.href);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-4">
        <div className="w-12 h-12 rounded-2xl bg-brand-600 animate-spin flex items-center justify-center text-white shadow-lg shadow-brand-500/25 mb-4">
          <QrCode className="w-6 h-6 animate-pulse" />
        </div>
        <p className="text-slate-600 font-semibold text-base">Loading Your Token...</p>
      </div>
    );
  }

  if (error || !positionInfo) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-4 text-center">
        <div className="w-14 h-14 rounded-3xl bg-rose-100 text-rose-600 flex items-center justify-center mb-4">
          <AlertTriangle className="w-7 h-7" />
        </div>
        <h2 className="text-xl font-bold text-slate-800 mb-2">Token Not Found</h2>
        <p className="text-sm text-slate-500 max-w-sm mb-6">
          {error || 'This token tracking session does not exist or has expired.'}
        </p>
        <Link
          to="/"
          className="px-6 py-3 rounded-2xl bg-brand-600 text-white font-semibold text-sm shadow-md"
        >
          Go to QueueLess Home
        </Link>
      </div>
    );
  }

  const isCalled = positionInfo.status === 'CALLED';
  const isServing = positionInfo.status === 'SERVING';
  const isCompleted = positionInfo.status === 'COMPLETED';
  const isLeft = positionInfo.status === 'LEFT' || positionInfo.status === 'CANCELLED';

  return (
    <div className="min-h-screen bg-gradient-to-b from-brand-50/40 via-slate-50 to-slate-100 flex flex-col justify-between p-4 sm:p-6 selection:bg-brand-500 selection:text-white">
      {/* Top Header */}
      <div className="max-w-md w-full mx-auto flex items-center justify-between py-2">
        <div className="flex items-center gap-2">
          <div className="w-8 h-8 rounded-xl bg-brand-600 text-white flex items-center justify-center shadow-md shadow-brand-500/20">
            <QrCode className="w-4 h-4" />
          </div>
          <span className="font-extrabold text-slate-900 tracking-tight text-lg">QueueLess</span>
        </div>

        <div className="flex items-center gap-2">
          <button
            onClick={handleCopyLink}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-2xl bg-white border border-slate-200 text-slate-700 text-xs font-semibold shadow-sm hover:bg-slate-50 transition-colors"
            title="Share / Save your token link"
          >
            {copied ? (
              <>
                <Check className="w-3.5 h-3.5 text-emerald-600" />
                <span className="text-emerald-700">Copied!</span>
              </>
            ) : (
              <>
                <Copy className="w-3.5 h-3.5 text-slate-500" />
                <span>Save Link</span>
              </>
            )}
          </button>

          <button
            onClick={toggleLanguage}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-2xl bg-white border border-slate-200 text-slate-700 text-xs font-semibold shadow-sm hover:bg-slate-50 transition-colors"
          >
            <Globe className="w-3.5 h-3.5 text-brand-600" />
            <span>{language === 'en' ? 'हिंदी' : 'EN'}</span>
          </button>
        </div>
      </div>

      {/* Main Token Tracking Card */}
      <div className="max-w-md w-full mx-auto my-auto py-4">
        <div className="bg-white rounded-3xl shadow-xl shadow-brand-950/5 border border-slate-200/80 overflow-hidden">
          {/* Shop Header Banner */}
          <div className="p-6 text-center border-b border-slate-100 bg-slate-50/60">
            <span className="text-xs uppercase tracking-wider font-bold px-3 py-1 rounded-full bg-brand-50 text-brand-700 border border-brand-200">
              Live Token Register
            </span>
            <h1 className="text-2xl font-black text-slate-900 mt-2">
              {positionInfo.shopName}
            </h1>

            {/* Live SSE status indicator */}
            <div className="mt-2 inline-flex items-center gap-2 text-xs font-semibold text-slate-500">
              {sseConnected ? (
                <>
                  <span className="w-2 h-2 rounded-full bg-emerald-500 animate-pulse" />
                  <span className="text-emerald-700 font-bold">{t('liveUpdatesActive')}</span>
                </>
              ) : (
                <>
                  <span className="w-2 h-2 rounded-full bg-amber-400" />
                  <span>{t('reconnecting')}</span>
                </>
              )}
            </div>
          </div>

          {/* TURN ALERT BANNER (If Called or Serving) */}
          {isCalled && (
            <div className="p-4 bg-brand-600 text-white text-center animate-bounce-short">
              <div className="flex items-center justify-center gap-2 font-black text-lg">
                <Volume2 className="w-5 h-5 animate-pulse" />
                <span>{t('turnAlertHeading')}</span>
              </div>
              <p className="text-sm font-medium text-brand-100 mt-0.5">
                {t('turnAlertMsg')}
              </p>
            </div>
          )}

          {isServing && (
            <div className="p-4 bg-emerald-600 text-white text-center">
              <div className="flex items-center justify-center gap-2 font-black text-lg">
                <CheckCircle2 className="w-5 h-5" />
                <span>{t('servingAlertHeading')}</span>
              </div>
            </div>
          )}

          {/* GIANT TOKEN DISPLAY */}
          <div className="p-8 text-center bg-gradient-to-b from-white to-slate-50/50">
            <p className="text-xs uppercase font-extrabold text-slate-400 tracking-wider">
              {t('yourToken')}
            </p>
            <div className="my-4 inline-block">
              <div
                className={`px-8 py-5 rounded-3xl font-black text-6xl sm:text-7xl shadow-lg tracking-tight ${
                  isServing
                    ? 'bg-emerald-600 text-white shadow-emerald-500/30'
                    : isCalled
                    ? 'bg-brand-600 text-white shadow-brand-500/30 ring-8 ring-brand-300/40 animate-pulse'
                    : 'bg-brand-50 text-brand-700 border-2 border-brand-200'
                }`}
              >
                #{positionInfo.tokenNumber}
              </div>
            </div>

            <div className="mt-1">
              <StatusBadge status={positionInfo.status} size="lg" />
            </div>
          </div>

          {/* Stats Grid: Currently Serving, People Ahead, Wait Time */}
          {!isCompleted && !isLeft && (
            <div className="grid grid-cols-3 divide-x divide-slate-100 border-t border-b border-slate-100 bg-white">
              {/* Serving Now */}
              <div className="p-4 text-center">
                <p className="text-[11px] font-bold text-slate-400 uppercase">Now Serving</p>
                <p className="text-xl sm:text-2xl font-black text-brand-600 mt-1">
                  {positionInfo.currentlyServingToken !== '-'
                    ? `#${positionInfo.currentlyServingToken}`
                    : '-'}
                </p>
              </div>

              {/* People Ahead */}
              <div className="p-4 text-center">
                <p className="text-[11px] font-bold text-slate-400 uppercase">Ahead of You</p>
                <p className="text-xl sm:text-2xl font-black text-slate-800 mt-1">
                  {positionInfo.peopleAhead}
                </p>
              </div>

              {/* Est. Wait */}
              <div className="p-4 text-center">
                <p className="text-[11px] font-bold text-slate-400 uppercase">Est. Wait</p>
                <p className="text-xl sm:text-2xl font-black text-amber-600 mt-1">
                  ~{positionInfo.estimatedWaitMinutes}m
                </p>
              </div>
            </div>
          )}

          {/* Estimate Disclaimer */}
          {!isCompleted && !isLeft && (
            <div className="p-4 bg-slate-50/70 text-center">
              <p className="text-xs text-slate-400 font-medium italic">
                * {t('waitDisclaimer')}
              </p>
            </div>
          )}

          {/* Footer Action: Leave Queue or Rejoin */}
          <div className="p-6 bg-white border-t border-slate-100 text-center">
            {isLeft ? (
              <button
                onClick={handleRejoinQueue}
                className="w-full py-3.5 px-4 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm shadow-md transition-all flex items-center justify-center gap-2"
              >
                <RotateCcw className="w-4 h-4" />
                <span>{t('rejoinQueueBtn')}</span>
              </button>
            ) : !isCompleted ? (
              <button
                onClick={() => setConfirmLeaveOpen(true)}
                className="text-xs font-semibold text-slate-400 hover:text-rose-600 transition-colors inline-flex items-center gap-1.5"
              >
                <LogOut className="w-3.5 h-3.5" />
                <span>{t('leaveQueueBtn')}</span>
              </button>
            ) : (
              <p className="text-sm font-bold text-emerald-600">
                Thank you! We look forward to serving you again.
              </p>
            )}
          </div>
        </div>
      </div>

      {/* Confirmation Modal to Leave Queue */}
      <ConfirmModal
        isOpen={confirmLeaveOpen}
        title={t('leaveQueueBtn')}
        message={t('confirmLeaveQueue')}
        confirmText="Yes, Leave Queue"
        confirmVariant="danger"
        onClose={() => setConfirmLeaveOpen(false)}
        onConfirm={handleLeaveQueue}
      />

      <footer className="text-center py-4 text-xs font-semibold text-slate-400">
        QueueLess — Digital Token System
      </footer>
    </div>
  );
};

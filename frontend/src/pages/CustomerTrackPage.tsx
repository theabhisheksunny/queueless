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
  Clock,
  Bell,
  BellRing,
  Smartphone,
  X,
} from 'lucide-react';
import {
  checkPushStatus,
  subscribeCustomerPush,
  unsubscribeCustomerPush,
  clearActiveQueueNotifications,
  type PushStatus,
} from '../utils/pushManager';

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
  const [isExpired, setIsExpired] = useState(false);
  const [copied, setCopied] = useState(false);
  const [confirmLeaveOpen, setConfirmLeaveOpen] = useState(false);
  const [sseConnected, setSseConnected] = useState(false);
  const [pushStatus, setPushStatus] = useState<PushStatus>('loading');
  const [isSubscribingPush, setIsSubscribingPush] = useState(false);
  const [pushDismissed, setPushDismissed] = useState(() => {
    return trackingToken ? sessionStorage.getItem('push_dismissed_' + trackingToken) === 'true' : false;
  });

  // Check Web Push subscription on mount
  useEffect(() => {
    if (!trackingToken) return;
    checkPushStatus(trackingToken).then((status) => {
      setPushStatus(status);
    });
  }, [trackingToken]);

  // Reconcile notifications when offline devices reconnect or when token reaches terminal state or expires
  useEffect(() => {
    const isTerminalStatus =
      positionInfo?.status === 'COMPLETED' ||
      positionInfo?.status === 'LEFT' ||
      positionInfo?.status === 'CANCELLED' ||
      positionInfo?.status === 'NO_SHOW';

    if (isExpired || isTerminalStatus) {
      clearActiveQueueNotifications(trackingToken);
    }
  }, [isExpired, positionInfo?.status, trackingToken]);

  const handleEnablePush = async () => {
    if (!trackingToken) return;
    setIsSubscribingPush(true);
    try {
      const res = await subscribeCustomerPush(trackingToken);
      if (res.success) {
        setPushStatus('subscribed');
      } else if (res.error === 'ios_needs_pwa') {
        setPushStatus('ios_needs_pwa');
      } else if (typeof Notification !== 'undefined' && Notification.permission === 'denied') {
        setPushStatus('denied');
      }
    } catch (e) {
      console.error(e);
    } finally {
      setIsSubscribingPush(false);
    }
  };

  const handleDisablePush = async () => {
    if (!trackingToken) return;
    const ok = await unsubscribeCustomerPush(trackingToken);
    if (ok) {
      setPushStatus('unsubscribed');
    }
  };

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
      if (err.status === 410 || (err.message && err.message.toLowerCase().includes('expired'))) {
        setIsExpired(true);
        setError(null);
      } else {
        setError(err.message || 'Token not found');
      }
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
      // Re-verify position with backend in case stream ended due to token expiration
      fetchPosition();
    };

    return () => {
      eventSource.close();
    };
  }, [trackingToken, fetchPosition]);

  // Automatic timer for completed token expiration
  useEffect(() => {
    if (!positionInfo || positionInfo.status !== 'COMPLETED') return;

    let expiryMs: number | null = null;
    if (positionInfo.expiresAt) {
      expiryMs = new Date(positionInfo.expiresAt).getTime() - Date.now();
    } else if (positionInfo.completedAt) {
      expiryMs = new Date(positionInfo.completedAt).getTime() + 15 * 60 * 1000 - Date.now();
    }

    if (expiryMs !== null) {
      if (expiryMs <= 0) {
        setIsExpired(true);
      } else {
        const timer = setTimeout(() => {
          fetchPosition();
        }, expiryMs);
        return () => clearTimeout(timer);
      }
    }
  }, [positionInfo, fetchPosition]);

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

  if (isExpired) {
    return (
      <div className="min-h-screen bg-gradient-to-b from-slate-50 via-white to-slate-100 flex flex-col justify-between p-4 sm:p-6 selection:bg-brand-500 selection:text-white">
        {/* Top Header */}
        <div className="max-w-md w-full mx-auto flex items-center justify-between py-2">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-xl bg-brand-600 text-white flex items-center justify-center shadow-md shadow-brand-500/20">
              <QrCode className="w-4 h-4" />
            </div>
            <span className="font-extrabold text-slate-900 tracking-tight text-lg">QueueLess</span>
          </div>

          <button
            onClick={toggleLanguage}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-2xl bg-white border border-slate-200 text-slate-700 text-xs font-semibold shadow-sm hover:bg-slate-50 transition-colors"
          >
            <Globe className="w-3.5 h-3.5 text-brand-600" />
            <span>{language === 'en' ? 'हिंदी' : 'EN'}</span>
          </button>
        </div>

        {/* Expired Token Card */}
        <div className="max-w-md w-full mx-auto my-auto py-8">
          <div className="bg-white rounded-3xl shadow-xl shadow-brand-950/5 border border-slate-200/80 p-8 text-center space-y-4">
            <div className="w-16 h-16 rounded-3xl bg-amber-50 border border-amber-200 text-amber-600 mx-auto flex items-center justify-center shadow-sm">
              <Clock className="w-8 h-8" />
            </div>
            <h1 className="text-2xl font-black text-slate-900 tracking-tight">
              {t('tokenExpiredTitle')}
            </h1>
            <p className="text-sm font-medium text-slate-600 leading-relaxed max-w-sm mx-auto">
              {t('tokenExpiredMsg')}
            </p>
          </div>
        </div>

        <footer className="text-center py-4 text-xs font-semibold text-slate-400">
          QueueLess — Digital Token System
        </footer>
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
  const isLeft = positionInfo.status === 'LEFT';
  const isCancelled = positionInfo.status === 'CANCELLED';
  const isNoShow = positionInfo.status === 'NO_SHOW';
  const isTerminal = isCompleted || isLeft || isCancelled || isNoShow;

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
          {/* Subtle Bell Button to restore dismissed notification prompt */}
          {!isTerminal && pushStatus === 'unsubscribed' && pushDismissed && (
            <button
              onClick={() => {
                setPushDismissed(false);
                if (trackingToken) sessionStorage.removeItem('push_dismissed_' + trackingToken);
              }}
              className="flex items-center gap-1 px-2.5 py-1.5 rounded-2xl bg-white border border-slate-200 text-brand-600 hover:bg-slate-50 text-xs font-semibold shadow-sm transition-colors"
              title="Enable Live Notifications"
            >
              <Bell className="w-3.5 h-3.5" />
            </button>
          )}

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

          {/* CUSTOMER LIVE NOTIFICATIONS PROMPT & STATUS */}
          {!isTerminal && (
            <>
              {pushStatus === 'subscribed' && (
                <div className="mx-6 mt-4 p-3 bg-emerald-50/90 border border-emerald-200/80 rounded-2xl flex items-center justify-between text-xs font-semibold text-emerald-900 shadow-sm animate-fade-in">
                  <div className="flex items-center gap-2">
                    <span className="relative flex h-2 w-2">
                      <span className="animate-ping absolute inline-flex h-full w-full rounded-full bg-emerald-400 opacity-75"></span>
                      <span className="relative inline-flex rounded-full h-2 w-2 bg-emerald-500"></span>
                    </span>
                    <BellRing className="w-4 h-4 text-emerald-600 flex-shrink-0" />
                    <span>{t('notificationsActive')}</span>
                  </div>
                  <button
                    onClick={handleDisablePush}
                    className="text-[11px] font-bold text-slate-500 hover:text-rose-600 underline transition-colors ml-2"
                  >
                    {t('disableNotificationsBtn')}
                  </button>
                </div>
              )}

              {pushStatus === 'unsubscribed' && !pushDismissed && (
                <div className="mx-6 mt-4 p-4 bg-gradient-to-br from-indigo-50/90 via-white to-brand-50/60 border border-brand-200 rounded-2xl shadow-sm text-left relative overflow-hidden animate-fade-in">
                  <button
                    onClick={() => {
                      setPushDismissed(true);
                      if (trackingToken) sessionStorage.setItem('push_dismissed_' + trackingToken, 'true');
                    }}
                    className="absolute top-2.5 right-2.5 text-slate-400 hover:text-slate-600 p-1 rounded-lg"
                    title="Dismiss"
                  >
                    <X className="w-4 h-4" />
                  </button>
                  <div className="flex items-start gap-3">
                    <div className="w-9 h-9 rounded-xl bg-brand-600 text-white flex items-center justify-center flex-shrink-0 shadow-md shadow-brand-500/25">
                      <Bell className="w-4 h-4" />
                    </div>
                    <div className="flex-1 pr-4">
                      <h4 className="text-xs sm:text-sm font-extrabold text-slate-900 tracking-tight">
                        {t('pushInvitationTitle')}
                      </h4>
                      <p className="text-[11px] sm:text-xs text-slate-600 font-medium mt-0.5 leading-relaxed">
                        {t('pushInvitationDesc')}
                      </p>
                      <div className="mt-3 flex items-center gap-2">
                        <button
                          onClick={handleEnablePush}
                          disabled={isSubscribingPush}
                          className="px-3.5 py-1.5 rounded-xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-xs shadow-sm flex items-center gap-1.5 transition-colors disabled:opacity-60"
                        >
                          <Bell className="w-3 h-3" />
                          <span>{isSubscribingPush ? 'Enabling...' : t('enableNotificationsBtn')}</span>
                        </button>
                        <button
                          onClick={() => {
                            setPushDismissed(true);
                            if (trackingToken) sessionStorage.setItem('push_dismissed_' + trackingToken, 'true');
                          }}
                          className="px-2.5 py-1.5 rounded-xl text-slate-500 hover:text-slate-700 font-semibold text-xs transition-colors"
                        >
                          {t('notNowBtn')}
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
              )}

              {pushStatus === 'ios_needs_pwa' && !pushDismissed && (
                <div className="mx-6 mt-4 p-3.5 bg-blue-50 border border-blue-200 rounded-2xl flex items-start gap-2.5 text-xs text-blue-900 font-medium">
                  <Smartphone className="w-4 h-4 text-blue-600 mt-0.5 flex-shrink-0" />
                  <div className="flex-1">
                    <span>{t('iosPwaHint')}</span>
                  </div>
                  <button
                    onClick={() => {
                      setPushDismissed(true);
                      if (trackingToken) sessionStorage.setItem('push_dismissed_' + trackingToken, 'true');
                    }}
                    className="text-blue-400 hover:text-blue-600 p-1"
                  >
                    <X className="w-3.5 h-3.5" />
                  </button>
                </div>
              )}

              {pushStatus === 'unsupported' && !pushDismissed && (
                <div className="mx-6 mt-4 p-3 bg-slate-50 border border-slate-200/80 rounded-2xl flex items-center justify-between text-xs text-slate-500 font-medium">
                  <span>{t('pushUnsupported')}</span>
                  <button
                    onClick={() => {
                      setPushDismissed(true);
                      if (trackingToken) sessionStorage.setItem('push_dismissed_' + trackingToken, 'true');
                    }}
                    className="text-slate-400 hover:text-slate-600 p-1 ml-2"
                  >
                    <X className="w-3.5 h-3.5" />
                  </button>
                </div>
              )}
            </>
          )}

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
          {!isTerminal && (
            <div className="grid grid-cols-3 divide-x divide-slate-100 border-t border-b border-slate-100 bg-white">
              {/* Serving Now */}
              <div className="p-4 text-center">
                <p className="text-[11px] font-bold text-slate-400 uppercase">Now Serving</p>
                <p className="text-xl sm:text-2xl font-black text-brand-600 mt-1">
                  {positionInfo.currentlyServingToken && positionInfo.currentlyServingToken !== '-'
                    ? positionInfo.currentlyServingToken
                        .split(', ')
                        .map((t) => (t.startsWith('#') ? t : `#${t}`))
                        .join(', ')
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
          {!isTerminal && (
            <div className="p-4 bg-slate-50/70 text-center">
              <p className="text-xs text-slate-400 font-medium italic">
                * {t('waitDisclaimer')}
              </p>
            </div>
          )}

          {/* Footer Action: Leave Queue, Rejoin, or Terminal Info */}
          <div className="p-6 bg-white border-t border-slate-100 text-center">
            {isLeft ? (
              <button
                onClick={handleRejoinQueue}
                className="w-full py-3.5 px-4 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm shadow-md transition-all flex items-center justify-center gap-2"
              >
                <RotateCcw className="w-4 h-4" />
                <span>{t('rejoinQueueBtn')}</span>
              </button>
            ) : isCompleted ? (
              <div className="space-y-2">
                <p className="text-base font-bold text-emerald-700">
                  {t('serviceCompletedMsg')}
                </p>
                <p className="text-xs text-slate-400 font-medium">
                  {t('completedGraceNotice')}
                </p>
              </div>
            ) : isNoShow ? (
              <div className="space-y-2">
                <p className="text-base font-bold text-rose-600">
                  Marked as No-Show
                </p>
                <p className="text-xs text-slate-400 font-medium">
                  You missed your turn. Please scan the shop QR code if you would like to join again.
                </p>
              </div>
            ) : isCancelled ? (
              <div className="space-y-2">
                <p className="text-base font-bold text-slate-600">
                  Token Cancelled
                </p>
                <p className="text-xs text-slate-400 font-medium">
                  This token was cancelled. Please scan the shop QR code if you need assistance.
                </p>
              </div>
            ) : (
              <button
                onClick={() => setConfirmLeaveOpen(true)}
                className="text-xs font-semibold text-slate-400 hover:text-rose-600 transition-colors inline-flex items-center gap-1.5"
              >
                <LogOut className="w-3.5 h-3.5" />
                <span>{t('leaveQueueBtn')}</span>
              </button>
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

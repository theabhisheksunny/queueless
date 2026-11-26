import React, { useRef, useState } from 'react';
import { useLanguage } from '../context/LanguageContext';
import { QRCodeSVG, QRCodeCanvas } from 'qrcode.react';
import { X, Printer, Download, Copy, Check, ExternalLink, Sparkles } from 'lucide-react';
import { getTheme } from '../theme/theme';

interface QrModalProps {
  isOpen: boolean;
  onClose: () => void;
  shopName: string;
  category: string;
  publicQueueId: string;
  publicMerchantCode?: string;
  canonicalJoinUrl?: string;
}

export const QrModal: React.FC<QrModalProps> = ({
  isOpen,
  onClose,
  shopName,
  category,
  publicQueueId,
  publicMerchantCode,
  canonicalJoinUrl,
}) => {
  const { t } = useLanguage();
  const [copied, setCopied] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);
  const theme = getTheme(category);
  const ThemeIcon = theme.icon;

  if (!isOpen) return null;

  // Use opaque permanent QR URL whenever available, falling back to session publicQueueId
  const joinUrl = canonicalJoinUrl
    ? `${window.location.origin}${canonicalJoinUrl}`
    : publicMerchantCode
    ? `${window.location.origin}/join/shop/${publicMerchantCode}`
    : `${window.location.origin}/join/${publicQueueId}`;

  const handleCopy = () => {
    navigator.clipboard.writeText(joinUrl);
    setCopied(true);
    setTimeout(() => setCopied(false), 2500);
  };

  const handleDownload = () => {
    const canvas = canvasRef.current?.querySelector('canvas');
    if (canvas) {
      const pngUrl = canvas.toDataURL('image/png');
      const downloadLink = document.createElement('a');
      downloadLink.href = pngUrl;
      downloadLink.download = `QueueLess-QR-${shopName.replace(/\s+/g, '_')}.png`;
      document.body.appendChild(downloadLink);
      downloadLink.click();
      document.body.removeChild(downloadLink);
    }
  };

  const handlePrint = () => {
    window.print();
  };

  return (
    <div
      data-theme={theme.id}
      className="fixed inset-0 z-50 flex items-start sm:items-center justify-center p-3 sm:p-4 overflow-y-auto bg-slate-900/60 backdrop-blur-sm animate-fade-in print:p-0 print:bg-white print:static"
      style={{
        paddingTop: 'max(0.75rem, env(safe-area-inset-top, 0.75rem))',
        paddingBottom: 'max(0.75rem, env(safe-area-inset-bottom, 0.75rem))',
        paddingLeft: 'max(0.75rem, env(safe-area-inset-left, 0.75rem))',
        paddingRight: 'max(0.75rem, env(safe-area-inset-right, 0.75rem))',
      }}
    >
      <div className="bg-white rounded-3xl shadow-2xl border border-slate-100 max-w-lg w-full max-h-[calc(100dvh-max(1.5rem,env(safe-area-inset-top,1.5rem)+env(safe-area-inset-bottom,1.5rem)))] sm:max-h-[92vh] flex flex-col my-auto overflow-hidden print:shadow-none print:border-none print:max-w-none print:w-full">
        {/* Header - Hidden in Print */}
        <div className="px-5 py-3 sm:px-6 sm:py-4 border-b border-slate-100 flex items-center justify-between bg-slate-50/80 backdrop-blur-sm flex-shrink-0 sticky top-0 z-10 print:hidden">
          <div className="flex items-center gap-2">
            <span className={`w-2.5 h-2.5 rounded-full ${theme.styles.iconBg}`} />
            <h3 className="text-base sm:text-lg font-bold text-slate-900">{t('qrTitle')}</h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            data-testid="close-qr-modal"
            className="min-w-[44px] min-h-[44px] w-11 h-11 flex items-center justify-center rounded-2xl text-slate-400 hover:text-slate-600 hover:bg-slate-100 active:bg-slate-200 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Printable Placard Container */}
        <div className="p-4 sm:p-6 md:p-8 text-center flex flex-col items-center overflow-y-auto flex-1 min-h-0 print:p-12">
          {/* Shop Header */}
          <div className="mb-4">
            <div className="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-bold border uppercase tracking-wider mb-2">
              <ThemeIcon className="w-3.5 h-3.5" />
              <span>{category || theme.categoryLabel}</span>
            </div>
            <h2 className="text-2xl sm:text-3xl font-extrabold text-slate-900 mt-1">
              {shopName}
            </h2>
            <p className="text-sm font-medium text-slate-500 mt-1">
              {t('qrSubtitle')}
            </p>
          </div>

          {/* QR Code Canvas Card */}
          <div className={`p-3 sm:p-5 rounded-3xl ${theme.styles.qrFrame} inline-block my-2`}>
            <div ref={canvasRef} className="hidden">
              {/* Hidden canvas for downloading crisp PNG */}
              <QRCodeCanvas value={joinUrl} size={600} level="H" includeMargin />
            </div>
            {/* Crisp SVG QR code for sharp rendering on mobile and desktop */}
            <div className="bg-white p-2 rounded-2xl">
              <QRCodeSVG
                value={joinUrl}
                size={180}
                level="H"
                includeMargin
                className="sm:hidden rounded-xl"
              />
              <QRCodeSVG
                value={joinUrl}
                size={220}
                level="H"
                includeMargin
                className="hidden sm:block rounded-xl"
              />
            </div>
          </div>

          {/* Instruction Pill */}
          <div className="mt-4 px-4 py-2 rounded-2xl bg-slate-50 border border-slate-200/80 text-slate-700 text-sm font-semibold inline-flex items-center gap-2">
            <Sparkles className={`w-4 h-4 ${theme.styles.iconText}`} />
            <span>Scan with mobile camera to take a token</span>
          </div>

          {/* Direct Link display */}
          <p className="text-xs text-slate-400 mt-3 font-mono break-all max-w-sm">
            {joinUrl}
          </p>

          <p className={`text-xs font-bold mt-4 tracking-wide uppercase ${theme.styles.iconText}`}>
            Powered by QueueLess Token System
          </p>
        </div>

        {/* Action Buttons - Hidden in Print */}
        <div className="p-4 sm:p-6 bg-slate-50 border-t border-slate-100 flex flex-wrap gap-2 sm:gap-2.5 flex-shrink-0 print:hidden">
          <button
            type="button"
            onClick={handlePrint}
            className={`flex-1 min-w-[120px] sm:min-w-[130px] inline-flex items-center justify-center gap-2 py-2.5 sm:py-3 px-3 sm:px-4 rounded-2xl font-semibold text-xs sm:text-sm shadow-md transition-all min-h-[44px] ${theme.styles.primaryButton}`}
          >
            <Printer className="w-4 h-4" />
            <span>{t('printPoster')}</span>
          </button>

          <button
            type="button"
            onClick={handleDownload}
            className="flex-1 min-w-[120px] sm:min-w-[130px] inline-flex items-center justify-center gap-2 py-2.5 sm:py-3 px-3 sm:px-4 rounded-2xl bg-white hover:bg-slate-100 border border-slate-200 text-slate-700 font-semibold text-xs sm:text-sm transition-colors min-h-[44px]"
          >
            <Download className="w-4 h-4 text-slate-500" />
            <span>{t('downloadQr')}</span>
          </button>

          <button
            type="button"
            onClick={handleCopy}
            className="inline-flex items-center justify-center gap-2 py-2.5 sm:py-3 px-3 sm:px-4 rounded-2xl bg-white hover:bg-slate-100 border border-slate-200 text-slate-700 font-semibold text-xs sm:text-sm transition-colors min-h-[44px]"
            title={t('copyJoinLink')}
          >
            {copied ? (
              <>
                <Check className="w-4 h-4 text-emerald-600" />
                <span className="text-emerald-700">{t('copied')}</span>
              </>
            ) : (
              <>
                <Copy className="w-4 h-4 text-slate-500" />
                <span>Copy</span>
              </>
            )}
          </button>

          <a
            href={joinUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="p-2.5 sm:p-3 rounded-2xl bg-white hover:bg-slate-100 border border-slate-200 text-slate-600 transition-colors min-w-[44px] min-h-[44px] inline-flex items-center justify-center"
            title={t('openCustomerView')}
          >
            <ExternalLink className="w-4 h-4" />
          </a>
        </div>
      </div>
    </div>
  );
};

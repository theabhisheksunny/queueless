import React, { useRef, useState } from 'react';
import { useLanguage } from '../context/LanguageContext';
import { QRCodeSVG, QRCodeCanvas } from 'qrcode.react';
import { X, Printer, Download, Copy, Check, ExternalLink, Sparkles } from 'lucide-react';

interface QrModalProps {
  isOpen: boolean;
  onClose: () => void;
  shopName: string;
  category: string;
  publicQueueId: string;
}

export const QrModal: React.FC<QrModalProps> = ({
  isOpen,
  onClose,
  shopName,
  category,
  publicQueueId,
}) => {
  const { t } = useLanguage();
  const [copied, setCopied] = useState(false);
  const canvasRef = useRef<HTMLDivElement>(null);

  if (!isOpen) return null;

  const joinUrl = `${window.location.origin}/join/${publicQueueId}`;

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
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-fade-in print:p-0 print:bg-white print:static">
      <div className="bg-white rounded-3xl shadow-2xl border border-slate-100 max-w-lg w-full overflow-hidden print:shadow-none print:border-none print:max-w-none print:w-full">
        {/* Header - Hidden in Print */}
        <div className="px-6 py-5 border-b border-slate-100 flex items-center justify-between bg-slate-50/50 print:hidden">
          <h3 className="text-lg font-bold text-slate-900">{t('qrTitle')}</h3>
          <button
            onClick={onClose}
            className="p-1.5 rounded-xl text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {/* Printable Placard Container */}
        <div className="p-6 md:p-8 text-center flex flex-col items-center print:p-12">
          {/* Shop Header */}
          <div className="mb-4">
            <span className="text-xs uppercase tracking-wider font-bold px-3 py-1 rounded-full bg-brand-50 text-brand-700 border border-brand-200">
              {category}
            </span>
            <h2 className="text-2xl sm:text-3xl font-extrabold text-slate-900 mt-2">
              {shopName}
            </h2>
            <p className="text-sm font-medium text-slate-500 mt-1">
              {t('qrSubtitle')}
            </p>
          </div>

          {/* QR Code Canvas Card */}
          <div className="p-5 rounded-3xl bg-white border-2 border-brand-200 shadow-lg shadow-brand-500/10 inline-block my-2">
            <div ref={canvasRef} className="hidden">
              {/* Hidden canvas for downloading crisp PNG */}
              <QRCodeCanvas value={joinUrl} size={600} level="H" includeMargin />
            </div>
            {/* Crisp SVG QR code for sharp rendering on high-DPI screens and print */}
            <QRCodeSVG
              value={joinUrl}
              size={220}
              level="H"
              includeMargin
              className="rounded-xl"
            />
          </div>

          {/* Instruction Pill */}
          <div className="mt-4 px-4 py-2 rounded-2xl bg-slate-50 border border-slate-200/80 text-slate-700 text-sm font-semibold inline-flex items-center gap-2">
            <Sparkles className="w-4 h-4 text-brand-600" />
            <span>Scan with mobile camera to take a token</span>
          </div>

          {/* Direct Link display */}
          <p className="text-xs text-slate-400 mt-3 font-mono break-all max-w-sm">
            {joinUrl}
          </p>

          <p className="text-xs font-semibold text-brand-700 mt-4 tracking-wide uppercase">
            Powered by QueueLess Token System
          </p>
        </div>

        {/* Action Buttons - Hidden in Print */}
        <div className="p-6 bg-slate-50 border-t border-slate-100 flex flex-wrap gap-2.5 print:hidden">
          <button
            onClick={handlePrint}
            className="flex-1 min-w-[130px] inline-flex items-center justify-center gap-2 py-3 px-4 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-semibold text-sm shadow-md shadow-brand-500/20 transition-all"
          >
            <Printer className="w-4 h-4" />
            <span>{t('printPoster')}</span>
          </button>

          <button
            onClick={handleDownload}
            className="flex-1 min-w-[130px] inline-flex items-center justify-center gap-2 py-3 px-4 rounded-2xl bg-white hover:bg-slate-100 border border-slate-200 text-slate-700 font-semibold text-sm transition-colors"
          >
            <Download className="w-4 h-4 text-slate-500" />
            <span>{t('downloadQr')}</span>
          </button>

          <button
            onClick={handleCopy}
            className="inline-flex items-center justify-center gap-2 py-3 px-4 rounded-2xl bg-white hover:bg-slate-100 border border-slate-200 text-slate-700 font-semibold text-sm transition-colors"
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
                <span>Copy Link</span>
              </>
            )}
          </button>

          <a
            href={joinUrl}
            target="_blank"
            rel="noopener noreferrer"
            className="p-3 rounded-2xl bg-white hover:bg-slate-100 border border-slate-200 text-slate-600 transition-colors"
            title={t('openCustomerView')}
          >
            <ExternalLink className="w-4 h-4" />
          </a>
        </div>
      </div>
    </div>
  );
};

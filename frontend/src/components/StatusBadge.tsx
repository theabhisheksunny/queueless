import React from 'react';
import { useLanguage } from '../context/LanguageContext';
import { getThemeId, type ThemeId } from '../theme/theme';

interface StatusBadgeProps {
  status: string;
  size?: 'sm' | 'md' | 'lg';
  category?: string;
  themeId?: ThemeId;
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({
  status,
  size = 'md',
  category,
  themeId: propThemeId,
}) => {
  const { t } = useLanguage();
  const themeId = propThemeId || getThemeId(category);

  const getStyle = () => {
    switch (status) {
      case 'WAITING':
        return 'bg-amber-50 text-amber-800 border-amber-200';
      case 'CALLED': {
        switch (themeId) {
          case 'barber':
            return 'bg-amber-100 text-amber-950 border-amber-300 ring-2 ring-amber-400/50 animate-pulse font-bold';
          case 'salon':
            return 'bg-rose-50 text-rose-900 border-rose-300 ring-2 ring-rose-300/60 animate-pulse font-bold';
          case 'clinic':
            return 'bg-teal-50 text-teal-900 border-teal-300 ring-2 ring-teal-300/60 animate-pulse font-bold';
          case 'restaurant':
            return 'bg-orange-50 text-orange-950 border-orange-300 ring-2 ring-orange-300/60 animate-pulse font-bold';
          case 'retail':
            return 'bg-sky-50 text-sky-950 border-sky-300 ring-2 ring-sky-300/60 animate-pulse font-bold';
          default:
            return 'bg-brand-50 text-brand-700 border-brand-300 ring-2 ring-brand-400/40 animate-pulse font-bold';
        }
      }
      case 'SERVING':
        return 'bg-emerald-50 text-emerald-800 border-emerald-300 font-bold';
      case 'COMPLETED':
        return 'bg-slate-100 text-slate-600 border-slate-200';
      case 'SKIPPED':
        return 'bg-orange-50 text-orange-800 border-orange-200';
      case 'NO_SHOW':
        return 'bg-rose-50 text-rose-800 border-rose-200';
      case 'LEFT':
      case 'CANCELLED':
        return 'bg-slate-100 text-slate-500 border-slate-200 line-through';
      default:
        return 'bg-slate-100 text-slate-600 border-slate-200';
    }
  };

  const sizeClasses = {
    sm: 'px-2 py-0.5 text-xs',
    md: 'px-2.5 py-1 text-xs md:text-sm',
    lg: 'px-3 py-1.5 text-sm md:text-base font-medium',
  };

  return (
    <span
      className={`inline-flex items-center rounded-full border font-medium transition-all ${sizeClasses[size]} ${getStyle()}`}
    >
      {t(status as any) || status}
    </span>
  );
};

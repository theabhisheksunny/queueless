import React from 'react';
import { useLanguage } from '../context/LanguageContext';

interface StatusBadgeProps {
  status: string;
  size?: 'sm' | 'md' | 'lg';
}

export const StatusBadge: React.FC<StatusBadgeProps> = ({ status, size = 'md' }) => {
  const { t } = useLanguage();

  const getStyle = () => {
    switch (status) {
      case 'WAITING':
        return 'bg-amber-50 text-amber-700 border-amber-200';
      case 'CALLED':
        return 'bg-brand-50 text-brand-700 border-brand-300 ring-2 ring-brand-400/40 animate-pulse';
      case 'SERVING':
        return 'bg-emerald-50 text-emerald-700 border-emerald-300 font-semibold';
      case 'COMPLETED':
        return 'bg-slate-100 text-slate-600 border-slate-200';
      case 'SKIPPED':
        return 'bg-orange-50 text-orange-700 border-orange-200';
      case 'NO_SHOW':
        return 'bg-rose-50 text-rose-700 border-rose-200';
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

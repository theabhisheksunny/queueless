import React from 'react';
import {
  Scissors,
  Sparkles,
  Stethoscope,
  UtensilsCrossed,
  Wrench,
  QrCode,
} from 'lucide-react';

export type ThemeId = 'barber' | 'salon' | 'clinic' | 'restaurant' | 'retail' | 'default';

export interface ThemeColors {
  primary: string;
  primaryHover: string;
  primaryLight: string;
  primaryBorder: string;
  primaryText: string;
  accent: string;
  ring: string;
}

export interface ThemeStyles {
  bannerGradient: string;
  bannerSubtext: string;
  bannerTag: string;
  primaryButton: string;
  secondaryButton: string;
  accentButton: string;
  tokenHeroCard: string;
  tokenHeroText: string;
  tokenHeroBorder: string;
  badge: string;
  cardBorder: string;
  cardBorderHover: string;
  cardHighlight: string;
  calledHighlight: string;
  servingHighlight: string;
  pageBgGradient: string;
  iconBg: string;
  iconText: string;
  focusRing: string;
  qrFrame: string;
}

export interface ThemeDefinition {
  id: ThemeId;
  name: string;
  categoryLabel: string;
  icon: React.ComponentType<{ className?: string }>;
  colors: ThemeColors;
  styles: ThemeStyles;
}

export const THEMES: Record<ThemeId, ThemeDefinition> = {
  barber: {
    id: 'barber',
    name: 'Barber Shop',
    categoryLabel: 'Barber Shop',
    icon: Scissors,
    colors: {
      primary: '#d97706',
      primaryHover: '#b45309',
      primaryLight: '#fffbeb',
      primaryBorder: '#fde68a',
      primaryText: '#92400e',
      accent: '#fbbf24',
      ring: 'rgba(217, 119, 6, 0.25)',
    },
    styles: {
      bannerGradient: 'bg-gradient-to-tr from-slate-950 via-slate-900 to-amber-950 text-white border-b border-amber-500/20',
      bannerSubtext: 'text-amber-200/90',
      bannerTag: 'bg-amber-400/15 text-amber-300 border-amber-400/30 backdrop-blur-md',
      primaryButton: 'bg-amber-500 hover:bg-amber-400 text-slate-950 font-black shadow-lg shadow-amber-500/25 active:scale-[0.98]',
      secondaryButton: 'bg-slate-900 hover:bg-slate-800 text-amber-300 border border-amber-400/30 font-bold',
      accentButton: 'bg-gradient-to-r from-amber-600 to-amber-700 hover:from-amber-700 hover:to-amber-800 text-white font-black shadow-md shadow-amber-600/25',
      tokenHeroCard: 'bg-slate-900 border-2 border-amber-400/40 text-amber-400 shadow-xl shadow-amber-950/20',
      tokenHeroText: 'text-amber-400',
      tokenHeroBorder: 'border-amber-400/40',
      badge: 'bg-amber-50 text-amber-900 border-amber-300 font-bold',
      cardBorder: 'border-slate-200/90',
      cardBorderHover: 'hover:border-amber-400/60',
      cardHighlight: 'bg-amber-50/40',
      calledHighlight: 'bg-amber-500 text-slate-950 ring-4 ring-amber-400/40',
      servingHighlight: 'bg-emerald-600 text-white shadow-emerald-500/25',
      pageBgGradient: 'bg-gradient-to-b from-amber-50/30 via-slate-50 to-slate-100',
      iconBg: 'bg-amber-100 text-amber-900',
      iconText: 'text-amber-600',
      focusRing: 'focus:border-amber-500 focus:ring-4 focus:ring-amber-500/15',
      qrFrame: 'bg-slate-900 text-white border-2 border-amber-400/30 shadow-xl shadow-amber-950/20',
    },
  },

  salon: {
    id: 'salon',
    name: 'Salon & Beauty',
    categoryLabel: 'Salon & Spa',
    icon: Sparkles,
    colors: {
      primary: '#e11d48',
      primaryHover: '#be123c',
      primaryLight: '#fff1f2',
      primaryBorder: '#fecdd3',
      primaryText: '#9f1239',
      accent: '#f43f5e',
      ring: 'rgba(225, 29, 72, 0.25)',
    },
    styles: {
      bannerGradient: 'bg-gradient-to-tr from-rose-700 via-rose-600 to-pink-600 text-white',
      bannerSubtext: 'text-rose-100',
      bannerTag: 'bg-white/20 text-white border-white/30 backdrop-blur-md',
      primaryButton: 'bg-rose-600 hover:bg-rose-700 text-white font-bold shadow-lg shadow-rose-600/25 active:scale-[0.98]',
      secondaryButton: 'bg-white hover:bg-rose-50 text-rose-700 border-2 border-rose-200 font-bold',
      accentButton: 'bg-gradient-to-r from-rose-500 to-pink-600 hover:from-rose-600 hover:to-pink-700 text-white font-bold shadow-md shadow-rose-500/25',
      tokenHeroCard: 'bg-rose-50/70 border-2 border-rose-300 text-rose-900 shadow-xl shadow-rose-950/5',
      tokenHeroText: 'text-rose-900',
      tokenHeroBorder: 'border-rose-300',
      badge: 'bg-rose-50 text-rose-800 border-rose-200 font-bold',
      cardBorder: 'border-rose-100/90',
      cardBorderHover: 'hover:border-rose-300',
      cardHighlight: 'bg-rose-50/30',
      calledHighlight: 'bg-rose-600 text-white ring-4 ring-rose-300/40',
      servingHighlight: 'bg-emerald-600 text-white shadow-emerald-500/25',
      pageBgGradient: 'bg-gradient-to-b from-rose-50/40 via-pink-50/20 to-slate-100',
      iconBg: 'bg-rose-100 text-rose-700',
      iconText: 'text-rose-600',
      focusRing: 'focus:border-rose-500 focus:ring-4 focus:ring-rose-500/15',
      qrFrame: 'bg-white text-slate-900 border-2 border-rose-200 shadow-xl shadow-rose-500/10',
    },
  },

  clinic: {
    id: 'clinic',
    name: 'Clinic & Healthcare',
    categoryLabel: 'Clinic / Healthcare',
    icon: Stethoscope,
    colors: {
      primary: '#0d9488',
      primaryHover: '#0f766e',
      primaryLight: '#f0fdfa',
      primaryBorder: '#99f6e4',
      primaryText: '#115e59',
      accent: '#0284c7',
      ring: 'rgba(13, 148, 136, 0.25)',
    },
    styles: {
      bannerGradient: 'bg-gradient-to-tr from-teal-800 via-teal-700 to-cyan-700 text-white',
      bannerSubtext: 'text-teal-100',
      bannerTag: 'bg-white/20 text-white border-white/30 backdrop-blur-md',
      primaryButton: 'bg-teal-600 hover:bg-teal-700 text-white font-bold shadow-lg shadow-teal-600/25 active:scale-[0.98]',
      secondaryButton: 'bg-white hover:bg-teal-50 text-teal-800 border-2 border-teal-200 font-bold',
      accentButton: 'bg-gradient-to-r from-teal-600 to-cyan-600 hover:from-teal-700 hover:to-cyan-700 text-white font-bold shadow-md shadow-teal-600/25',
      tokenHeroCard: 'bg-teal-50/80 border-2 border-teal-300 text-teal-950 shadow-xl shadow-teal-950/5',
      tokenHeroText: 'text-teal-950',
      tokenHeroBorder: 'border-teal-300',
      badge: 'bg-teal-50 text-teal-800 border-teal-200 font-bold',
      cardBorder: 'border-teal-100/90',
      cardBorderHover: 'hover:border-teal-300',
      cardHighlight: 'bg-teal-50/30',
      calledHighlight: 'bg-teal-600 text-white ring-4 ring-teal-300/40',
      servingHighlight: 'bg-emerald-600 text-white shadow-emerald-500/25',
      pageBgGradient: 'bg-gradient-to-b from-teal-50/40 via-cyan-50/20 to-slate-100',
      iconBg: 'bg-teal-100 text-teal-800',
      iconText: 'text-teal-600',
      focusRing: 'focus:border-teal-500 focus:ring-4 focus:ring-teal-500/15',
      qrFrame: 'bg-white text-slate-900 border-2 border-teal-200 shadow-xl shadow-teal-500/10',
    },
  },

  restaurant: {
    id: 'restaurant',
    name: 'Restaurant & Café',
    categoryLabel: 'Restaurant / Cafe',
    icon: UtensilsCrossed,
    colors: {
      primary: '#c2410c',
      primaryHover: '#9a3412',
      primaryLight: '#fff7ed',
      primaryBorder: '#fed7aa',
      primaryText: '#9a3412',
      accent: '#d97706',
      ring: 'rgba(194, 65, 12, 0.25)',
    },
    styles: {
      bannerGradient: 'bg-gradient-to-tr from-orange-800 via-amber-800 to-stone-800 text-white',
      bannerSubtext: 'text-orange-100',
      bannerTag: 'bg-white/20 text-white border-white/30 backdrop-blur-md',
      primaryButton: 'bg-orange-600 hover:bg-orange-700 text-white font-bold shadow-lg shadow-orange-600/25 active:scale-[0.98]',
      secondaryButton: 'bg-white hover:bg-orange-50 text-orange-800 border-2 border-orange-200 font-bold',
      accentButton: 'bg-gradient-to-r from-orange-600 to-amber-600 hover:from-orange-700 hover:to-amber-700 text-white font-bold shadow-md shadow-orange-600/25',
      tokenHeroCard: 'bg-orange-50/80 border-2 border-orange-300 text-orange-950 shadow-xl shadow-orange-950/5',
      tokenHeroText: 'text-orange-950',
      tokenHeroBorder: 'border-orange-300',
      badge: 'bg-orange-50 text-orange-800 border-orange-200 font-bold',
      cardBorder: 'border-orange-100/90',
      cardBorderHover: 'hover:border-orange-300',
      cardHighlight: 'bg-orange-50/30',
      calledHighlight: 'bg-orange-600 text-white ring-4 ring-orange-300/40',
      servingHighlight: 'bg-emerald-600 text-white shadow-emerald-500/25',
      pageBgGradient: 'bg-gradient-to-b from-orange-50/40 via-amber-50/20 to-slate-100',
      iconBg: 'bg-orange-100 text-orange-800',
      iconText: 'text-orange-600',
      focusRing: 'focus:border-orange-500 focus:ring-4 focus:ring-orange-500/15',
      qrFrame: 'bg-white text-slate-900 border-2 border-orange-200 shadow-xl shadow-orange-500/10',
    },
  },

  retail: {
    id: 'retail',
    name: 'Retail & Electronics',
    categoryLabel: 'Retail & Electronics',
    icon: Wrench,
    colors: {
      primary: '#0284c7',
      primaryHover: '#0369a1',
      primaryLight: '#f0f9ff',
      primaryBorder: '#bae6fd',
      primaryText: '#075985',
      accent: '#06b6d4',
      ring: 'rgba(2, 132, 199, 0.25)',
    },
    styles: {
      bannerGradient: 'bg-gradient-to-tr from-slate-950 via-sky-950 to-blue-900 text-white',
      bannerSubtext: 'text-sky-200',
      bannerTag: 'bg-sky-400/20 text-sky-200 border-sky-400/30 backdrop-blur-md',
      primaryButton: 'bg-sky-600 hover:bg-sky-700 text-white font-bold shadow-lg shadow-sky-600/25 active:scale-[0.98]',
      secondaryButton: 'bg-white hover:bg-sky-50 text-sky-800 border-2 border-sky-200 font-bold',
      accentButton: 'bg-gradient-to-r from-sky-600 to-cyan-600 hover:from-sky-700 hover:to-cyan-700 text-white font-bold shadow-md shadow-sky-600/25',
      tokenHeroCard: 'bg-sky-50/80 border-2 border-sky-300 text-sky-950 shadow-xl shadow-sky-950/5',
      tokenHeroText: 'text-sky-950',
      tokenHeroBorder: 'border-sky-300',
      badge: 'bg-sky-50 text-sky-800 border-sky-200 font-bold',
      cardBorder: 'border-sky-100/90',
      cardBorderHover: 'hover:border-sky-300',
      cardHighlight: 'bg-sky-50/30',
      calledHighlight: 'bg-sky-600 text-white ring-4 ring-sky-300/40',
      servingHighlight: 'bg-emerald-600 text-white shadow-emerald-500/25',
      pageBgGradient: 'bg-gradient-to-b from-sky-50/40 via-cyan-50/20 to-slate-100',
      iconBg: 'bg-sky-100 text-sky-800',
      iconText: 'text-sky-600',
      focusRing: 'focus:border-sky-500 focus:ring-4 focus:ring-sky-500/15',
      qrFrame: 'bg-white text-slate-900 border-2 border-sky-200 shadow-xl shadow-sky-500/10',
    },
  },

  default: {
    id: 'default',
    name: 'QueueLess Brand',
    categoryLabel: 'Other Local Business',
    icon: QrCode,
    colors: {
      primary: '#7c3aed',
      primaryHover: '#6d28d9',
      primaryLight: '#f5f3ff',
      primaryBorder: '#ddd6fe',
      primaryText: '#5b21b6',
      accent: '#8b5cf6',
      ring: 'rgba(124, 58, 237, 0.25)',
    },
    styles: {
      bannerGradient: 'bg-gradient-to-tr from-brand-700 via-brand-600 to-brand-500 text-white',
      bannerSubtext: 'text-brand-100',
      bannerTag: 'bg-white/20 text-white border-white/30 backdrop-blur-md',
      primaryButton: 'bg-brand-600 hover:bg-brand-700 text-white font-bold shadow-lg shadow-brand-500/25 active:scale-[0.98]',
      secondaryButton: 'bg-white hover:bg-slate-50 text-slate-800 border-2 border-slate-200 font-bold',
      accentButton: 'bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-600 hover:to-amber-700 text-white font-bold shadow-md shadow-amber-500/20',
      tokenHeroCard: 'bg-brand-50 border-2 border-brand-200 text-brand-700 shadow-xl shadow-brand-950/5',
      tokenHeroText: 'text-brand-700',
      tokenHeroBorder: 'border-brand-200',
      badge: 'bg-brand-50 text-brand-700 border-brand-200 font-bold',
      cardBorder: 'border-slate-200/80',
      cardBorderHover: 'hover:border-brand-200',
      cardHighlight: 'bg-brand-50/30',
      calledHighlight: 'bg-brand-600 text-white ring-4 ring-brand-300/40',
      servingHighlight: 'bg-emerald-600 text-white shadow-emerald-500/25',
      pageBgGradient: 'bg-gradient-to-b from-brand-50/40 via-slate-50 to-slate-100',
      iconBg: 'bg-brand-100 text-brand-700',
      iconText: 'text-brand-600',
      focusRing: 'focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15',
      qrFrame: 'bg-white text-slate-900 border-2 border-brand-200 shadow-xl shadow-brand-500/10',
    },
  },
};

/**
 * Resolves registered business category string to a supported theme identifier.
 * Maps all 8 backend categories:
 * - "Barber Shop" -> 'barber'
 * - "Salon & Spa" -> 'salon'
 * - "Clinic & Doctor" & "Small Hospital" -> 'clinic'
 * - "Restaurant / Cafe" -> 'restaurant'
 * - "Repair Shop" & "Automobile Service" -> 'retail'
 * - "Other Local Business" -> 'default'
 * - null / missing / unknown -> 'default'
 */
export function getThemeId(category?: string | null): ThemeId {
  if (!category) return 'default';
  const c = category.trim().toLowerCase();
  if (c === 'barber shop' || c === 'barber') return 'barber';
  if (c === 'salon & spa' || c === 'salon' || c === 'beauty salon' || c === 'salon / beauty' || c === 'salon & personal care') return 'salon';
  if (
    c === 'clinic & doctor' ||
    c === 'small hospital' ||
    c === 'clinic' ||
    c === 'hospital' ||
    c === 'clinic / healthcare' ||
    c === 'doctor' ||
    c === 'healthcare & medical'
  ) {
    return 'clinic';
  }
  if (c === 'restaurant / cafe' || c === 'restaurant / café' || c === 'restaurant' || c === 'cafe' || c === 'restaurant & food') return 'restaurant';
  if (
    c === 'repair shop' ||
    c === 'automobile service' ||
    c === 'automobile services' ||
    c === 'retail' ||
    c === 'repair' ||
    c === 'repair & maintenance' ||
    c === 'retail & shopping' ||
    c === 'retail / electronics' ||
    c === 'electronics'
  ) {
    return 'retail';
  }
  return 'default';
}

/**
 * Returns the full ThemeDefinition for a given category.
 */
export function getTheme(category?: string | null): ThemeDefinition {
  const id = getThemeId(category);
  return THEMES[id];
}

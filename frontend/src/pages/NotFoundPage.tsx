import React from 'react';
import { useNavigate } from 'react-router-dom';
import { Compass, Home, ArrowLeft } from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';

export const NotFoundPage: React.FC = () => {
  const navigate = useNavigate();
  const { isAuthenticated } = useAuth();
  const { language } = useLanguage();

  const isHindi = language === 'hi';

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col items-center justify-center p-4 selection:bg-brand-500 selection:text-white">
      <div className="max-w-md w-full text-center">
        {/* Visual Icon */}
        <div className="w-20 h-20 rounded-3xl bg-brand-50 border border-brand-100 flex items-center justify-center mx-auto mb-6 text-brand-600 shadow-xl shadow-brand-500/10">
          <Compass className="w-10 h-10 animate-spin-slow" />
        </div>

        {/* 404 Code Badge */}
        <div className="inline-flex items-center px-3 py-1 rounded-full text-xs font-bold uppercase tracking-wider bg-slate-200/80 text-slate-700 mb-4">
          404 — {isHindi ? 'त्रुटि' : 'Page Not Found'}
        </div>

        {/* Heading */}
        <h1 className="text-3xl font-extrabold text-slate-900 tracking-tight mb-3">
          {isHindi ? 'यह पृष्ठ उपलब्ध नहीं है' : 'Lost in the Queue?'}
        </h1>

        {/* Description */}
        <p className="text-slate-600 text-sm leading-relaxed mb-8 max-w-sm mx-auto">
          {isHindi
            ? 'आप जिस पृष्ठ या लिंक पर जाने की कोशिश कर रहे हैं वह मौजूद नहीं है, हटा दिया गया है, या URL में कोई गलत टाइपिंग हुई है।'
            : 'The URL you entered does not exist or may have been moved. If you were looking for a shop queue, please verify the QR tracking link.'}
        </p>

        {/* Action Buttons */}
        <div className="flex flex-col sm:flex-row items-center justify-center gap-3">
          <button
            onClick={() => navigate(-1)}
            className="w-full sm:w-auto px-5 py-3 rounded-2xl border border-slate-200 bg-white hover:bg-slate-50 text-slate-700 font-semibold text-sm transition-all flex items-center justify-center gap-2 shadow-sm"
          >
            <ArrowLeft className="w-4 h-4" />
            <span>{isHindi ? 'पीछे जाएं' : 'Go Back'}</span>
          </button>

          <button
            onClick={() => navigate(isAuthenticated ? '/' : '/login')}
            className="w-full sm:w-auto px-6 py-3 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-semibold text-sm shadow-md shadow-brand-500/20 transition-all flex items-center justify-center gap-2"
          >
            <Home className="w-4 h-4" />
            <span>{isAuthenticated ? (isHindi ? 'डैशबोर्ड पर जाएं' : 'Go to Dashboard') : (isHindi ? 'लॉगिन करें' : 'Sign In')}</span>
          </button>
        </div>

        {/* Brand Footer */}
        <div className="mt-12 text-xs text-slate-400 font-medium">
          QueueLess • Digital Token & Queue Operating System
        </div>
      </div>
    </div>
  );
};

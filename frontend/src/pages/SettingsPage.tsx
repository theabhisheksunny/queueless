import React, { useState, useEffect } from 'react';
import { api } from '../api/client';
import { useAuth } from '../context/AuthContext';
import { useLanguage } from '../context/LanguageContext';
import { Navbar } from '../components/Navbar';
import { Store, Clock, Save, CheckCircle2 } from 'lucide-react';

const CATEGORIES = [
  'Barber Shop',
  'Salon & Spa',
  'Clinic & Doctor',
  'Small Hospital',
  'Restaurant / Cafe',
  'Repair Shop',
  'Automobile Service',
  'Other Local Business',
];

export const SettingsPage: React.FC = () => {
  const { updateShopInfo } = useAuth();
  const { t } = useLanguage();

  const [shopName, setShopName] = useState('');
  const [category, setCategory] = useState(CATEGORIES[0]);
  const [avgServiceDurationMinutes, setAvgServiceDurationMinutes] = useState(15);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchSettings();
  }, []);

  const fetchSettings = async () => {
    try {
      const data = await api.getShopSettings();
      setShopName(data.name);
      setCategory(data.category);
      setAvgServiceDurationMinutes(data.avgServiceDurationMinutes);
    } catch (err: any) {
      setError(err.message || 'Failed to load shop settings');
    } finally {
      setIsLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!shopName.trim()) {
      setError('Shop name cannot be blank');
      return;
    }

    setIsSaving(true);
    setError(null);
    setMessage(null);
    try {
      const updated = await api.updateShopSettings({
        name: shopName.trim(),
        category,
        avgServiceDurationMinutes,
      });
      updateShopInfo(updated.name, updated.category);
      setMessage(t('settingsUpdated'));
    } catch (err: any) {
      setError(err.message || 'Failed to save settings');
    } finally {
      setIsSaving(false);
    }
  };

  if (isLoading) {
    return (
      <div className="min-h-screen bg-slate-50 flex items-center justify-center">
        <p className="text-slate-500 font-semibold">Loading settings...</p>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 flex flex-col">
      <Navbar />

      <main className="flex-1 max-w-2xl w-full mx-auto px-4 sm:px-6 py-6 md:py-8 space-y-6">
        <div>
          <h1 className="text-2xl sm:text-3xl font-extrabold text-slate-900">
            {t('settings')}
          </h1>
          <p className="text-sm text-slate-500 font-medium mt-1">
            Configure your shop profile and default service duration.
          </p>
        </div>

        <div className="bg-white rounded-3xl border border-slate-200/80 shadow-sm p-6 sm:p-8">
          {error && (
            <div className="mb-4 p-3.5 rounded-2xl bg-rose-50 border border-rose-200 text-rose-700 text-sm font-medium">
              {error}
            </div>
          )}

          {message && (
            <div className="mb-4 p-3.5 rounded-2xl bg-emerald-50 border border-emerald-200 text-emerald-800 text-sm font-semibold flex items-center gap-2">
              <CheckCircle2 className="w-5 h-5 text-emerald-600" />
              <span>{message}</span>
            </div>
          )}

          <form onSubmit={handleSubmit} className="space-y-5">
            <div>
              <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                {t('shopName')} <span className="text-rose-500">*</span>
              </label>
              <div className="relative">
                <Store className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                <input
                  type="text"
                  required
                  value={shopName}
                  onChange={(e) => setShopName(e.target.value)}
                  className="w-full pl-11 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-semibold transition-all"
                />
              </div>
            </div>

            <div>
              <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                {t('category')}
              </label>
              <select
                value={category}
                onChange={(e) => setCategory(e.target.value)}
                className="w-full px-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-semibold bg-white transition-all"
              >
                {CATEGORIES.map((cat) => (
                  <option key={cat} value={cat}>
                    {cat}
                  </option>
                ))}
              </select>
            </div>

            <div>
              <label className="block text-xs font-bold uppercase tracking-wider text-slate-700 mb-1.5">
                {t('avgServiceTime')} ({t('minutes')})
              </label>
              <div className="relative">
                <Clock className="w-5 h-5 text-slate-400 absolute left-3.5 top-1/2 -translate-y-1/2" />
                <input
                  type="number"
                  min="1"
                  max="180"
                  required
                  value={avgServiceDurationMinutes}
                  onChange={(e) => setAvgServiceDurationMinutes(Number(e.target.value))}
                  className="w-full pl-11 pr-4 py-3 rounded-2xl border border-slate-200 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/10 outline-none text-slate-900 font-semibold transition-all"
                />
              </div>
              <p className="text-xs text-slate-400 mt-1 pl-1">
                Used to estimate customer waiting times: (Customers Ahead × Avg. Service Time).
              </p>
            </div>

            <div className="pt-2">
              <button
                type="submit"
                disabled={isSaving}
                className="w-full py-3.5 px-4 rounded-2xl bg-brand-600 hover:bg-brand-700 text-white font-bold text-sm shadow-md shadow-brand-500/25 flex items-center justify-center gap-2 transition-all disabled:opacity-50"
              >
                <Save className="w-4 h-4" />
                <span>{isSaving ? 'Saving...' : t('saveSettings')}</span>
              </button>
            </div>
          </form>
        </div>
      </main>
    </div>
  );
};

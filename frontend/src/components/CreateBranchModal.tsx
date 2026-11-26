import React, { useState, useEffect } from 'react';
import { useAuth } from '../context/AuthContext';
import { api } from '../api/client';
import { X, Building2, MapPin, Phone, Tag, CheckCircle2, AlertCircle } from 'lucide-react';

interface CreateBranchModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreated: (branchId: number) => void;
}

const CATEGORIES = [
  'Salon & Personal Care',
  'Healthcare & Medical',
  'Restaurant & Food',
  'Retail & Shopping',
  'Repair & Maintenance',
  'Automobile Services',
  'Education & Coaching',
  'Professional Services',
  'Home & Local Services',
  'Other Business',
];

const generateBranchIdempotencyKey = (): string => {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return 'branch-' + Date.now() + '-' + Math.random().toString(36).substring(2, 9);
};

export const CreateBranchModal: React.FC<CreateBranchModalProps> = ({ isOpen, onClose, onCreated }) => {
  const { businesses, refreshBusinesses, switchShop } = useAuth();
  const [selectedBusinessId, setSelectedBusinessId] = useState<number | ''>(() => {
    return businesses[0] ? businesses[0].id : '';
  });
  const [newBusinessName, setNewBusinessName] = useState('');
  const [isCreatingNewBusiness, setIsCreatingNewBusiness] = useState(businesses.length === 0);
  const [branchName, setBranchName] = useState('');
  const [category, setCategory] = useState(CATEGORIES[0]);
  const [address, setAddress] = useState('');
  const [phone, setPhone] = useState('');
  const [avgServiceMinutes, setAvgServiceMinutes] = useState(15);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [idempotencyKey, setIdempotencyKey] = useState<string>(generateBranchIdempotencyKey);

  useEffect(() => {
    if (isOpen) {
      setIdempotencyKey(generateBranchIdempotencyKey());
      setError(null);
      setBranchName('');
      setAddress('');
      setPhone('');
      if (businesses.length > 0 && (selectedBusinessId === '' || !selectedBusinessId)) {
        setSelectedBusinessId(businesses[0].id);
        setIsCreatingNewBusiness(false);
      }
    }
  }, [isOpen, businesses]);

  if (!isOpen) return null;

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!branchName.trim()) {
      setError('Branch / Shop location name is required.');
      return;
    }

    setIsSubmitting(true);
    try {
      let bizId = typeof selectedBusinessId === 'number' ? selectedBusinessId : undefined;

      if (isCreatingNewBusiness) {
        if (!newBusinessName.trim()) {
          setError('Business brand name is required.');
          setIsSubmitting(false);
          return;
        }
        const createdBiz = await api.createBusiness({ name: newBusinessName.trim() });
        bizId = createdBiz.id;
      }

      const branch = await api.createBranch({
        businessId: bizId,
        name: branchName.trim(),
        branchName: branchName.trim(),
        category,
        address: address.trim() || undefined,
        phone: phone.trim() || undefined,
        avgServiceDurationMinutes: avgServiceMinutes,
        idempotencyKey,
      });

      await refreshBusinesses();
      switchShop(branch.id);
      onCreated(branch.id);
      setIdempotencyKey(generateBranchIdempotencyKey());
      setBranchName('');
      setAddress('');
      setPhone('');
      onClose();
    } catch (err: any) {
      setError(err.message || 'Failed to create branch. Please try again.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-in fade-in duration-150">
      <div className="bg-white rounded-2xl max-w-lg w-full p-6 shadow-2xl border border-slate-100 max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between pb-4 border-b border-slate-100">
          <div className="flex items-center gap-2">
            <div className="w-10 h-10 rounded-xl bg-brand-50 text-brand-600 flex items-center justify-center">
              <Building2 className="w-5 h-5" />
            </div>
            <div>
              <h3 className="text-lg font-bold text-slate-900">Add New Branch / Shop</h3>
              <p className="text-xs text-slate-500">Each branch gets its own QR code, queue & tokens</p>
            </div>
          </div>
          <button
            onClick={onClose}
            className="w-9 h-9 rounded-xl flex items-center justify-center text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
          >
            <X className="w-5 h-5" />
          </button>
        </div>

        {error && (
          <div className="mt-4 p-3 rounded-xl bg-rose-50 border border-rose-200/80 text-rose-700 text-xs sm:text-sm flex items-start gap-2">
            <AlertCircle className="w-4 h-4 mt-0.5 shrink-0" />
            <span>{error}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} className="mt-5 space-y-4">
          {/* Business Brand Selection */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
              Business Brand
            </label>
            {businesses.length > 0 && !isCreatingNewBusiness ? (
              <div className="space-y-2">
                <select
                  value={selectedBusinessId}
                  onChange={(e) => setSelectedBusinessId(Number(e.target.value))}
                  className="w-full px-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm bg-white"
                >
                  {businesses.map((biz) => (
                    <option key={biz.id} value={biz.id}>
                      {biz.name} ({biz.branches?.length || 0} branches)
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  onClick={() => setIsCreatingNewBusiness(true)}
                  className="text-xs text-brand-600 hover:text-brand-700 font-semibold"
                >
                  + Or create a new business brand
                </button>
              </div>
            ) : (
              <div className="space-y-2">
                <input
                  type="text"
                  placeholder="e.g. Apex Salons & Spas"
                  value={newBusinessName}
                  onChange={(e) => setNewBusinessName(e.target.value)}
                  className="w-full px-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm"
                  required
                />
                {businesses.length > 0 && (
                  <button
                    type="button"
                    onClick={() => setIsCreatingNewBusiness(false)}
                    className="text-xs text-slate-500 hover:text-slate-700 font-medium"
                  >
                    ← Select existing business brand
                  </button>
                )}
              </div>
            )}
          </div>

          {/* Branch / Shop Name */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
              Branch Name / Physical Location
            </label>
            <input
              type="text"
              placeholder="e.g. Downtown Mall, Sector 18"
              value={branchName}
              onChange={(e) => setBranchName(e.target.value)}
              className="w-full px-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm"
              required
            />
          </div>

          {/* Category */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
              Business Category & Theme
            </label>
            <div className="relative">
              <Tag className="w-4 h-4 text-slate-400 absolute left-3.5 top-3 pointer-events-none" />
              <select
                value={category}
                onChange={(e) => setCategory(e.target.value)}
                className="w-full pl-10 pr-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm bg-white"
              >
                {CATEGORIES.map((cat) => (
                  <option key={cat} value={cat}>
                    {cat}
                  </option>
                ))}
              </select>
            </div>
          </div>

          {/* Address */}
          <div>
            <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
              Branch Address <span className="text-slate-400 font-normal">(Optional)</span>
            </label>
            <div className="relative">
              <MapPin className="w-4 h-4 text-slate-400 absolute left-3.5 top-3 pointer-events-none" />
              <input
                type="text"
                placeholder="Shop No. 4, Ground Floor, MG Road"
                value={address}
                onChange={(e) => setAddress(e.target.value)}
                className="w-full pl-10 pr-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm"
              />
            </div>
          </div>

          {/* Phone */}
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                Contact Phone <span className="text-slate-400 font-normal">(Optional)</span>
              </label>
              <div className="relative">
                <Phone className="w-4 h-4 text-slate-400 absolute left-3.5 top-3 pointer-events-none" />
                <input
                  type="tel"
                  placeholder="9876543210"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  className="w-full pl-10 pr-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm"
                />
              </div>
            </div>

            <div>
              <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                Avg Service (Mins)
              </label>
              <input
                type="number"
                min="1"
                max="180"
                value={avgServiceMinutes}
                onChange={(e) => setAvgServiceMinutes(Number(e.target.value))}
                className="w-full px-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm"
              />
            </div>
          </div>

          {/* Pricing Notice */}
          <div className="p-3.5 rounded-xl bg-amber-50/80 border border-amber-200/80 text-xs text-amber-900 flex items-start gap-2.5">
            <CheckCircle2 className="w-4 h-4 text-amber-600 mt-0.5 shrink-0" />
            <div>
              <p className="font-semibold">One-time registration fee: ₹99 per additional branch</p>
              <p className="text-amber-700 mt-0.5">
                Lifetime registration. Operates under your main active subscription with zero recurring charges.
              </p>
            </div>
          </div>

          <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-100">
            <button
              type="button"
              onClick={onClose}
              disabled={isSubmitting}
              className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 hover:text-slate-800 hover:bg-slate-100 transition-colors"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={isSubmitting}
              className="px-5 py-2 rounded-xl text-sm font-semibold text-white bg-brand-600 hover:bg-brand-700 shadow-md shadow-brand-500/20 disabled:opacity-50 transition-colors flex items-center gap-1.5"
            >
              {isSubmitting ? 'Creating...' : 'Create Branch'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

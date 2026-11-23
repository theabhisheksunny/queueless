import React, { useState, useEffect, useCallback } from 'react';
import { useAuth } from '../context/AuthContext';
import { api, type StaffData, type SeatSummaryData, type DeviceReplacementData } from '../api/client';
import { Navbar } from '../components/Navbar';
import {
  Users,
  UserPlus,
  Shield,
  Smartphone,
  CheckCircle2,
  AlertCircle,
  X,
  CreditCard,
  Copy,
  Check,
  RefreshCw,
  Building2,
  Trash2,
  RotateCcw,
  ShieldCheck,
  ShieldAlert
} from 'lucide-react';
import { QRCodeSVG } from 'qrcode.react';

export const StaffPage: React.FC = () => {
  const { user, branches } = useAuth();
  const [staffList, setStaffList] = useState<StaffData[]>([]);
  const [seatSummary, setSeatSummary] = useState<SeatSummaryData | null>(null);
  const [pendingReplacements, setPendingReplacements] = useState<DeviceReplacementData[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // Modals state
  const [isAddStaffOpen, setIsAddStaffOpen] = useState(false);
  const [isPurchaseSeatOpen, setIsPurchaseSeatOpen] = useState(false);
  const [isEditBranchesOpen, setIsEditBranchesOpen] = useState(false);
  const [selectedStaff, setSelectedStaff] = useState<StaffData | null>(null);

  // Add staff form state
  const [fullName, setFullName] = useState('');
  const [phone, setPhone] = useState('');
  const [pin, setPin] = useState('');
  const [selectedBranchIds, setSelectedBranchIds] = useState<number[]>([]);
  const [isSubmittingStaff, setIsSubmittingStaff] = useState(false);

  // Purchase seat form state
  const [seatPlan, setSeatPlan] = useState<'MONTHLY' | 'YEARLY'>('MONTHLY');
  const [seatUtr, setSeatUtr] = useState('');
  const [isSubmittingSeat, setIsSubmittingSeat] = useState(false);
  const [copiedUpi, setCopiedUpi] = useState(false);

  const isOwner = user?.role === 'ROLE_OWNER';

  const fetchData = useCallback(async () => {
    setIsLoading(true);
    setError(null);
    try {
      const [staffData, seats, replacements] = await Promise.all([
        api.getStaff(),
        api.getSeatSummary(),
        api.getOwnerDeviceReplacements().catch(() => [] as DeviceReplacementData[]),
      ]);
      setStaffList(staffData);
      setSeatSummary(seats);
      setPendingReplacements(replacements);
    } catch (err: any) {
      setError(err.message || 'Failed to load staff and user seats.');
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (isOwner) {
      fetchData();
    }
  }, [isOwner, fetchData]);

  const handleApproveReplacement = async (id: number) => {
    setError(null);
    try {
      await api.approveOwnerDeviceReplacement(id);
      setSuccessMsg('Device replacement approved successfully. The staff member can now log in from their new device.');
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to approve device replacement.');
    }
  };

  const handleRejectReplacement = async (id: number) => {
    if (!window.confirm('Are you sure you want to reject this device replacement request?')) return;
    setError(null);
    try {
      await api.rejectOwnerDeviceReplacement(id, 'Rejected by shop owner');
      setSuccessMsg('Device replacement request rejected.');
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to reject device replacement.');
    }
  };

  const handleAddStaffSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccessMsg(null);

    const cleanPhone = phone.replace(/\D/g, '');
    const rawDigits = cleanPhone.startsWith('91') && cleanPhone.length === 12 ? cleanPhone.slice(2) : cleanPhone;
    if (rawDigits.length !== 10) {
      setError('Please enter a valid 10-digit Indian mobile number.');
      return;
    }

    if (!/^\d{6}$/.test(pin.trim())) {
      setError('Staff PIN must be exactly 6 numeric digits.');
      return;
    }

    if (selectedBranchIds.length === 0) {
      setError('Please select at least one branch for this staff member.');
      return;
    }

    setIsSubmittingStaff(true);
    try {
      await api.createStaff({
        fullName: fullName.trim(),
        phone: rawDigits,
        pin: pin.trim(),
        branchIds: selectedBranchIds,
      });

      setSuccessMsg(`Staff member ${fullName.trim()} added successfully.`);
      setIsAddStaffOpen(false);
      setFullName('');
      setPhone('');
      setPin('');
      setSelectedBranchIds([]);
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to create staff member.');
    } finally {
      setIsSubmittingStaff(false);
    }
  };

  const handlePurchaseSeatSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setSuccessMsg(null);

    if (!seatUtr.trim() || seatUtr.trim().length < 6) {
      setError('Please enter a valid 12-digit UPI Transaction Reference / UTR.');
      return;
    }

    setIsSubmittingSeat(true);
    try {
      await api.purchaseSeat({
        planType: seatPlan,
        paymentReference: seatUtr.trim(),
        paymentUpiId: seatSummary?.configuredUpiId || 'absunny@ybl',
      });

      setSuccessMsg('Additional seat payment submitted successfully. Awaiting administrative approval.');
      setIsPurchaseSeatOpen(false);
      setSeatUtr('');
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to submit seat payment.');
    } finally {
      setIsSubmittingSeat(false);
    }
  };

  const handleRevokeStaff = async (staff: StaffData) => {
    if (!window.confirm(`Are you sure you want to revoke access for ${staff.fullName}? Their device sessions will be invalidated immediately, and their user seat will be freed for reassignment.`)) {
      return;
    }
    setError(null);
    try {
      await api.revokeStaff(staff.id);
      setSuccessMsg(`Access revoked for ${staff.fullName}. Seat has been released for reassignment.`);
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to revoke staff access.');
    }
  };

  const handleResetDevice = async (staff: StaffData) => {
    if (!window.confirm(`Reset device session for ${staff.fullName}? This will log them out of their current device so they can sign in from a new device.`)) {
      return;
    }
    setError(null);
    try {
      await api.resetStaffDevice(staff.id);
      setSuccessMsg(`Device session reset for ${staff.fullName}. They can now log in on a new device.`);
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to reset staff device.');
    }
  };

  const handleEditBranchesSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedStaff) return;
    setError(null);
    try {
      await api.updateStaffBranches(selectedStaff.id, selectedBranchIds);
      setSuccessMsg(`Branch assignments updated for ${selectedStaff.fullName}.`);
      setIsEditBranchesOpen(false);
      setSelectedStaff(null);
      await fetchData();
    } catch (err: any) {
      setError(err.message || 'Failed to update branch assignments.');
    }
  };

  const copyUpiId = () => {
    const upi = seatSummary?.configuredUpiId || 'absunny@ybl';
    navigator.clipboard.writeText(upi);
    setCopiedUpi(true);
    setTimeout(() => setCopiedUpi(false), 2000);
  };

  const seatPrice = seatPlan === 'YEARLY' ? (seatSummary?.yearlyPrice || 494) : (seatSummary?.monthlyPrice || 47);
  const upiId = seatSummary?.configuredUpiId || 'absunny@ybl';
  const upiName = seatSummary?.configuredUpiName || 'QueueLess Token System';
  const qrPayload = `upi://pay?pa=${encodeURIComponent(upiId)}&pn=${encodeURIComponent(upiName)}&am=${seatPrice}&cu=INR&tn=${encodeURIComponent(`QueueLess Seat ${seatPlan}`)}`;

  if (!isOwner) {
    return (
      <div className="min-h-screen bg-slate-50 flex flex-col">
        <Navbar />
        <div className="max-w-xl mx-auto my-auto p-6 text-center">
          <Shield className="w-12 h-12 text-slate-400 mx-auto mb-3" />
          <h2 className="text-xl font-bold text-slate-800">Owner Access Only</h2>
          <p className="text-sm text-slate-500 mt-1">
            Staff and user seats management is reserved exclusively for the shop owner account.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50/70 pb-16">
      <Navbar />

      <main className="max-w-6xl mx-auto px-4 sm:px-6 pt-8 space-y-6">
        {/* Page Header */}
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <div className="flex items-center gap-2">
              <div className="w-9 h-9 rounded-xl bg-brand-50 text-brand-600 flex items-center justify-center">
                <Users className="w-5 h-5" />
              </div>
              <h1 className="text-2xl font-bold tracking-tight text-slate-900">
                Staff & User Seats
              </h1>
            </div>
            <p className="text-sm text-slate-500 mt-1">
              Add team operators with 6-digit PIN logins. 1 active device per seat with branch isolation.
            </p>
          </div>

          <div className="flex items-center gap-2.5">
            <button
              onClick={fetchData}
              disabled={isLoading}
              className="p-2.5 rounded-xl border border-slate-200 bg-white text-slate-600 hover:text-slate-900 hover:bg-slate-50 transition-colors"
              title="Refresh roster"
            >
              <RefreshCw className={`w-4 h-4 ${isLoading ? 'animate-spin' : ''}`} />
            </button>

            <button
              onClick={() => {
                setError(null);
                setSelectedBranchIds(branches.map((b) => b.id));
                setIsAddStaffOpen(true);
              }}
              className="px-4 py-2.5 rounded-xl bg-brand-600 hover:bg-brand-700 text-white font-semibold text-sm shadow-md shadow-brand-500/20 transition-colors flex items-center gap-2"
            >
              <UserPlus className="w-4 h-4" />
              <span>Add Staff</span>
            </button>
          </div>
        </div>

        {/* Alerts */}
        {error && (
          <div className="p-4 rounded-2xl bg-rose-50 border border-rose-200/80 text-rose-800 text-sm flex items-start gap-3">
            <AlertCircle className="w-5 h-5 text-rose-600 mt-0.5 shrink-0" />
            <div>
              <p className="font-semibold">Action Failed</p>
              <p className="mt-0.5 text-rose-700">{error}</p>
            </div>
          </div>
        )}

        {successMsg && (
          <div className="p-4 rounded-2xl bg-emerald-50 border border-emerald-200/80 text-emerald-800 text-sm flex items-start gap-3">
            <CheckCircle2 className="w-5 h-5 text-emerald-600 mt-0.5 shrink-0" />
            <div>
              <p className="font-semibold">Success</p>
              <p className="mt-0.5 text-emerald-700">{successMsg}</p>
            </div>
          </div>
        )}

        {/* Pending Device Replacement Requests */}
        {pendingReplacements && pendingReplacements.length > 0 && (
          <div className="bg-amber-50/70 border border-amber-200/80 rounded-2xl p-6 shadow-sm space-y-4">
            <div className="flex items-center gap-2.5">
              <div className="w-8 h-8 rounded-xl bg-amber-100 text-amber-800 flex items-center justify-center">
                <ShieldAlert className="w-4 h-4" />
              </div>
              <div>
                <h2 className="text-base font-bold text-amber-950">
                  Pending Device Replacement Requests ({pendingReplacements.length})
                </h2>
                <p className="text-xs text-amber-800">
                  Staff attempting to sign in on a new device require your explicit cryptographic approval.
                </p>
              </div>
            </div>

            <div className="grid gap-3 sm:grid-cols-2">
              {pendingReplacements.map((rep) => (
                <div key={rep.id} className="bg-white rounded-xl p-4 border border-amber-200/60 shadow-xs flex flex-col justify-between">
                  <div className="space-y-1.5">
                    <div className="flex items-center justify-between">
                      <span className="font-bold text-slate-900 text-sm">{rep.userFullName || 'Staff Member'}</span>
                      <span className="text-[11px] font-mono font-semibold px-2 py-0.5 rounded bg-slate-100 text-slate-700">
                        {rep.userPhone}
                      </span>
                    </div>

                    <div className="text-xs text-slate-600">
                      <span className="font-medium">Requested Device:</span> {rep.newDeviceName} ({rep.newDevicePlatform})
                    </div>
                    {rep.oldDeviceName && (
                      <div className="text-xs text-slate-400">
                        <span className="font-medium">Previous Device:</span> {rep.oldDeviceName}
                      </div>
                    )}
                    {rep.reason && (
                      <div className="text-xs text-slate-500 italic">
                        "{rep.reason}"
                      </div>
                    )}
                    <div className="text-[11px] text-slate-400">
                      Requested {new Date(rep.createdAt).toLocaleString()}
                    </div>
                  </div>

                  <div className="flex gap-2 pt-3 mt-3 border-t border-slate-100">
                    <button
                      type="button"
                      onClick={() => handleApproveReplacement(rep.id)}
                      className="flex-1 py-1.5 px-3 rounded-lg bg-emerald-600 hover:bg-emerald-700 text-white font-semibold text-xs transition-colors flex items-center justify-center gap-1 shadow-xs"
                    >
                      <Check className="w-3.5 h-3.5" />
                      <span>Approve</span>
                    </button>
                    <button
                      type="button"
                      onClick={() => handleRejectReplacement(rep.id)}
                      className="flex-1 py-1.5 px-3 rounded-lg border border-slate-200 hover:bg-slate-50 text-slate-700 font-semibold text-xs transition-colors flex items-center justify-center gap-1"
                    >
                      <X className="w-3.5 h-3.5" />
                      <span>Reject</span>
                    </button>
                  </div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* Seat Allocation Summary Card */}
        <div className="bg-white rounded-2xl p-6 shadow-sm border border-slate-200/80">
          <div className="flex flex-col md:flex-row md:items-center justify-between gap-6">
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4 flex-1">
              <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-100">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider block">
                  Included Seats
                </span>
                <span className="text-2xl font-black text-slate-900 mt-1 block">
                  {seatSummary?.includedSeats ?? 1}
                </span>
                <span className="text-[11px] text-slate-400 mt-0.5 block">with main plan</span>
              </div>

              <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-100">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider block">
                  Additional Seats
                </span>
                <span className="text-2xl font-black text-slate-900 mt-1 block">
                  {seatSummary?.additionalSeatsPurchased ?? 0}
                </span>
                <span className="text-[11px] text-slate-400 mt-0.5 block">purchased add-ons</span>
              </div>

              <div className="p-3.5 rounded-xl bg-slate-50 border border-slate-100">
                <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider block">
                  Assigned Staff
                </span>
                <span className="text-2xl font-black text-brand-600 mt-1 block">
                  {seatSummary?.assignedSeats ?? 0}
                </span>
                <span className="text-[11px] text-slate-400 mt-0.5 block">active staff users</span>
              </div>

              <div className="p-3.5 rounded-xl bg-emerald-50/80 border border-emerald-100">
                <span className="text-xs font-semibold text-emerald-700 uppercase tracking-wider block">
                  Available Seats
                </span>
                <span className="text-2xl font-black text-emerald-700 mt-1 block">
                  {seatSummary?.availableSeats ?? 0}
                </span>
                <span className="text-[11px] text-emerald-600 mt-0.5 block">ready to assign</span>
              </div>
            </div>

            <div className="flex flex-col sm:flex-row md:flex-col gap-2.5 sm:items-center md:items-end justify-center shrink-0 border-t md:border-t-0 md:border-l border-slate-100 pt-4 md:pt-0 md:pl-6">
              <button
                type="button"
                onClick={() => {
                  setError(null);
                  setIsPurchaseSeatOpen(true);
                }}
                className="w-full sm:w-auto px-4 py-2.5 rounded-xl bg-slate-900 hover:bg-slate-800 text-white font-semibold text-sm transition-colors flex items-center justify-center gap-2 shadow-sm"
              >
                <CreditCard className="w-4 h-4" />
                <span>Buy User Seat (₹47/mo)</span>
              </button>
              <span className="text-[11px] text-slate-400 text-center md:text-right block">
                ₹47 for 30 days • ₹494 for 365 days
              </span>
            </div>
          </div>
        </div>

        {/* Staff Roster Table */}
        <div className="bg-white rounded-2xl shadow-sm border border-slate-200/80 overflow-hidden">
          <div className="px-6 py-4 border-b border-slate-100 flex items-center justify-between">
            <h2 className="text-base font-bold text-slate-900">
              Staff Members ({staffList.length})
            </h2>
            <span className="text-xs text-slate-400">
              Active sessions limited to 1 device per seat
            </span>
          </div>

          {isLoading ? (
            <div className="p-12 text-center text-slate-400 text-sm">
              <RefreshCw className="w-6 h-6 animate-spin mx-auto mb-2 text-brand-600" />
              <span>Loading staff roster...</span>
            </div>
          ) : staffList.length === 0 ? (
            <div className="p-12 text-center">
              <Users className="w-12 h-12 text-slate-300 mx-auto mb-3" />
              <h3 className="text-base font-bold text-slate-800">No staff accounts yet</h3>
              <p className="text-xs text-slate-500 max-w-sm mx-auto mt-1 mb-4">
                Add counter staff or operators who can manage queues at your physical branches using their phone and PIN.
              </p>
              <button
                onClick={() => {
                  setError(null);
                  setSelectedBranchIds(branches.map((b) => b.id));
                  setIsAddStaffOpen(true);
                }}
                className="px-4 py-2 rounded-xl bg-brand-50 hover:bg-brand-100 text-brand-700 font-semibold text-xs transition-colors inline-flex items-center gap-1.5"
              >
                <UserPlus className="w-3.5 h-3.5" />
                <span>Add Your First Staff Member</span>
              </button>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="bg-slate-50/70 border-b border-slate-100 text-[11px] font-bold text-slate-500 uppercase tracking-wider">
                    <th className="py-3 px-6">Staff Member</th>
                    <th className="py-3 px-6">Mobile & PIN</th>
                    <th className="py-3 px-6">Assigned Branches</th>
                    <th className="py-3 px-6">Device & Enrollment</th>
                    <th className="py-3 px-6 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100">
                  {staffList.map((staff) => {
                    const isRevoked = staff.status === 'REVOKED';
                    const hasActiveSession = !!staff.activeSessionId;

                    return (
                      <tr key={staff.id} className={`hover:bg-slate-50/60 transition-colors ${isRevoked ? 'opacity-60 bg-slate-50/40' : ''}`}>
                        <td className="py-4 px-6">
                          <div className="font-semibold text-slate-900">{staff.fullName}</div>
                          <div className="text-xs text-slate-400 mt-0.5">
                            Added {staff.createdAt ? new Date(staff.createdAt).toLocaleDateString() : 'Recently'}
                          </div>
                        </td>

                        <td className="py-4 px-6">
                          <div className="font-mono text-xs font-semibold text-slate-800">{staff.phone}</div>
                          <div className="text-[11px] text-slate-400">
                            {staff.deviceEnrollmentStatus === 'AWAITING_ENROLLMENT' && staff.temporaryPin
                              ? `Temp PIN: ${staff.temporaryPin}`
                              : '6-digit PIN protected'}
                          </div>
                        </td>

                        <td className="py-4 px-6">
                          <div className="flex flex-wrap gap-1 max-w-xs">
                            {staff.assignedBranchNames?.length > 0 ? (
                              staff.assignedBranchNames.map((name, i) => (
                                <span
                                  key={i}
                                  className="text-[11px] px-2 py-0.5 rounded-md bg-slate-100 text-slate-700 font-medium border border-slate-200/60"
                                >
                                  {name}
                                </span>
                              ))
                            ) : (
                              <span className="text-xs text-slate-400 italic">No branches</span>
                            )}
                          </div>
                        </td>

                        <td className="py-4 px-6">
                          {isRevoked ? (
                            <span className="inline-flex items-center gap-1 text-[11px] font-bold px-2 py-0.5 rounded bg-rose-50 text-rose-700 border border-rose-200/60">
                              Revoked
                            </span>
                          ) : staff.hasPendingReplacement ? (
                            <span className="inline-flex items-center gap-1 text-[11px] font-bold px-2 py-0.5 rounded bg-amber-50 text-amber-700 border border-amber-200/60 animate-pulse">
                              <ShieldAlert className="w-3 h-3" />
                              Replacement Pending
                            </span>
                          ) : staff.deviceEnrollmentStatus === 'AWAITING_ENROLLMENT' ? (
                            <span className="inline-flex items-center gap-1 text-[11px] font-bold px-2 py-0.5 rounded bg-blue-50 text-blue-700 border border-blue-200/60">
                              <Smartphone className="w-3 h-3" />
                              Awaiting Enrollment
                            </span>
                          ) : staff.deviceEnrollmentStatus === 'ENROLLED' ? (
                            <div>
                              <span className="inline-flex items-center gap-1 text-[11px] font-bold px-2 py-0.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200/60">
                                <ShieldCheck className="w-3 h-3" />
                                {hasActiveSession ? 'Active Session' : 'Enrolled'}
                              </span>
                              <div className="text-[10px] text-slate-400 mt-0.5 max-w-[150px] truncate" title={staff.registeredDeviceName}>
                                {staff.registeredDeviceName || 'Registered Device'}
                              </div>
                            </div>
                          ) : hasActiveSession ? (
                            <span className="inline-flex items-center gap-1 text-[11px] font-bold px-2 py-0.5 rounded bg-emerald-50 text-emerald-700 border border-emerald-200/60">
                              <Smartphone className="w-3 h-3" />
                              1 Active Device
                            </span>
                          ) : (
                            <span className="text-xs text-slate-400">Signed out</span>
                          )}
                        </td>

                        <td className="py-4 px-6 text-right space-x-2">
                          {!isRevoked && (
                            <>
                              <button
                                type="button"
                                onClick={() => {
                                  setSelectedStaff(staff);
                                  setSelectedBranchIds(staff.assignedBranchIds || []);
                                  setIsEditBranchesOpen(true);
                                }}
                                className="px-2.5 py-1 rounded-lg text-xs font-medium text-slate-600 hover:text-slate-900 hover:bg-slate-100 transition-colors"
                              >
                                Branches
                              </button>

                              <button
                                type="button"
                                onClick={() => handleResetDevice(staff)}
                                className="px-2.5 py-1 rounded-lg text-xs font-medium text-amber-700 hover:bg-amber-50 transition-colors inline-flex items-center gap-1"
                                title="Reset active device session"
                              >
                                <RotateCcw className="w-3 h-3" />
                                <span>Reset Device</span>
                              </button>

                              <button
                                type="button"
                                onClick={() => handleRevokeStaff(staff)}
                                className="px-2.5 py-1 rounded-lg text-xs font-medium text-rose-600 hover:bg-rose-50 transition-colors inline-flex items-center gap-1"
                                title="Revoke access and free seat"
                              >
                                <Trash2 className="w-3 h-3" />
                                <span>Revoke</span>
                              </button>
                            </>
                          )}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          )}
        </div>

        {/* Informative Security Notice */}
        <div className="p-4 rounded-2xl bg-slate-100/70 border border-slate-200/80 text-xs text-slate-600 space-y-1">
          <p className="font-semibold text-slate-800">Security & Authorization Architecture:</p>
          <ul className="list-disc list-inside space-y-0.5 text-slate-500">
            <li>Staff log in using their 10-digit mobile number and 6-digit PIN. No SMS or OTP gateways are required.</li>
            <li>Each staff seat is locked to exactly 1 active device session. Logging in from a second device automatically invalidates the first.</li>
            <li>Staff can only operate queues at branches you assign. They have zero access to billing, subscription, or settings.</li>
            <li>Revoking a staff member immediately terminates all active sessions and releases their user seat for reassignment.</li>
          </ul>
        </div>
      </main>

      {/* Add Staff Modal */}
      {isAddStaffOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-white rounded-2xl max-w-md w-full p-6 shadow-2xl border border-slate-100 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between pb-4 border-b border-slate-100">
              <div className="flex items-center gap-2">
                <div className="w-10 h-10 rounded-xl bg-brand-50 text-brand-600 flex items-center justify-center">
                  <UserPlus className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-slate-900">Add Staff Operator</h3>
                  <p className="text-xs text-slate-500">6-digit PIN authentication (Zero OTP)</p>
                </div>
              </div>
              <button
                onClick={() => setIsAddStaffOpen(false)}
                className="w-9 h-9 rounded-xl flex items-center justify-center text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleAddStaffSubmit} className="mt-5 space-y-4">
              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                  Full Name
                </label>
                <input
                  type="text"
                  placeholder="e.g. Ramesh Kumar"
                  value={fullName}
                  onChange={(e) => setFullName(e.target.value)}
                  className="w-full px-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm"
                  required
                />
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                  10-Digit Mobile Number
                </label>
                <div className="flex items-center">
                  <span className="px-3.5 py-2.5 bg-slate-100 border border-r-0 border-slate-200 rounded-l-xl text-slate-600 text-sm font-semibold">
                    +91
                  </span>
                  <input
                    type="tel"
                    placeholder="9876543210"
                    value={phone}
                    onChange={(e) => setPhone(e.target.value)}
                    maxLength={10}
                    className="w-full px-3.5 py-2.5 rounded-r-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm font-mono"
                    required
                  />
                </div>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                  6-Digit Secret PIN
                </label>
                <input
                  type="password"
                  placeholder="••••••"
                  value={pin}
                  onChange={(e) => setPin(e.target.value)}
                  maxLength={6}
                  className="w-full px-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm tracking-widest font-mono"
                  required
                />
                <span className="text-[11px] text-slate-400 mt-1 block">
                  Staff member will use this PIN to log in on their device.
                </span>
              </div>

              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-2">
                  Assign Authorized Branches
                </label>
                <div className="space-y-2 max-h-40 overflow-y-auto border border-slate-200 rounded-xl p-3 bg-slate-50/50">
                  {branches.map((b) => {
                    const checked = selectedBranchIds.includes(b.id);
                    return (
                      <label key={b.id} className="flex items-center gap-2.5 cursor-pointer text-xs">
                        <input
                          type="checkbox"
                          checked={checked}
                          onChange={(e) => {
                            if (e.target.checked) {
                              setSelectedBranchIds([...selectedBranchIds, b.id]);
                            } else {
                              setSelectedBranchIds(selectedBranchIds.filter((id) => id !== b.id));
                            }
                          }}
                          className="w-4 h-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500"
                        />
                        <span className="font-semibold text-slate-800">{b.branchName || b.name}</span>
                        <span className="text-slate-400">({b.category})</span>
                      </label>
                    );
                  })}
                </div>
              </div>

              <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-100">
                <button
                  type="button"
                  onClick={() => setIsAddStaffOpen(false)}
                  disabled={isSubmittingStaff}
                  className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 hover:text-slate-800 hover:bg-slate-100 transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isSubmittingStaff}
                  className="px-5 py-2 rounded-xl text-sm font-semibold text-white bg-brand-600 hover:bg-brand-700 shadow-md shadow-brand-500/20 disabled:opacity-50 transition-colors"
                >
                  {isSubmittingStaff ? 'Creating...' : 'Create Staff User'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Purchase Seat Modal */}
      {isPurchaseSeatOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-white rounded-2xl max-w-md w-full p-6 shadow-2xl border border-slate-100 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between pb-4 border-b border-slate-100">
              <div className="flex items-center gap-2">
                <div className="w-10 h-10 rounded-xl bg-brand-50 text-brand-600 flex items-center justify-center">
                  <CreditCard className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-slate-900">Purchase User Seat</h3>
                  <p className="text-xs text-slate-500">Additional staff seat for your team</p>
                </div>
              </div>
              <button
                onClick={() => setIsPurchaseSeatOpen(false)}
                className="w-9 h-9 rounded-xl flex items-center justify-center text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handlePurchaseSeatSubmit} className="mt-5 space-y-4">
              {/* Plan Choice */}
              <div className="grid grid-cols-2 gap-3">
                <button
                  type="button"
                  onClick={() => setSeatPlan('MONTHLY')}
                  className={`p-3.5 rounded-xl border text-left transition-all ${
                    seatPlan === 'MONTHLY'
                      ? 'border-brand-600 bg-brand-50/50 ring-2 ring-brand-500/20'
                      : 'border-slate-200 hover:border-slate-300'
                  }`}
                >
                  <span className="text-xs font-semibold text-slate-500 uppercase block">Monthly</span>
                  <span className="text-xl font-black text-slate-900 mt-0.5 block">₹47</span>
                  <span className="text-[11px] text-slate-400 block">30 days access</span>
                </button>

                <button
                  type="button"
                  onClick={() => setSeatPlan('YEARLY')}
                  className={`p-3.5 rounded-xl border text-left transition-all relative ${
                    seatPlan === 'YEARLY'
                      ? 'border-brand-600 bg-brand-50/50 ring-2 ring-brand-500/20'
                      : 'border-slate-200 hover:border-slate-300'
                  }`}
                >
                  <span className="absolute -top-2 right-2 px-1.5 py-0.2 rounded-full bg-emerald-600 text-white text-[9px] font-black uppercase tracking-wider">
                    Save 12%
                  </span>
                  <span className="text-xs font-semibold text-slate-500 uppercase block">Yearly</span>
                  <span className="text-xl font-black text-slate-900 mt-0.5 block">₹494</span>
                  <span className="text-[11px] text-slate-400 block">365 days access</span>
                </button>
              </div>

              {/* QR Code Container */}
              <div className="p-4 rounded-2xl bg-slate-50 border border-slate-200/80 text-center flex flex-col items-center">
                <div className="p-2.5 bg-white rounded-xl shadow-sm border border-slate-100">
                  <QRCodeSVG value={qrPayload} size={150} level="H" />
                </div>

                <div className="mt-3 flex items-center gap-1.5 px-3 py-1.5 bg-white rounded-xl border border-slate-200 text-xs font-mono">
                  <span className="text-slate-700 font-semibold">{upiId}</span>
                  <button
                    type="button"
                    onClick={copyUpiId}
                    className="p-1 hover:text-brand-600 transition-colors"
                    title="Copy UPI ID"
                  >
                    {copiedUpi ? <Check className="w-3.5 h-3.5 text-emerald-600" /> : <Copy className="w-3.5 h-3.5" />}
                  </button>
                </div>
                <span className="text-[11px] text-slate-500 mt-1">
                  Pay ₹{seatPrice} using any UPI app (GPay, PhonePe, Paytm, BHIM)
                </span>
              </div>

              {/* UTR Input */}
              <div>
                <label className="block text-xs font-semibold text-slate-700 uppercase tracking-wider mb-1.5">
                  UPI Reference / UTR Number
                </label>
                <input
                  type="text"
                  placeholder="12-digit transaction reference (e.g. 432198765432)"
                  value={seatUtr}
                  onChange={(e) => setSeatUtr(e.target.value)}
                  className="w-full px-3.5 py-2.5 rounded-xl border border-slate-200 focus:outline-none focus:ring-2 focus:ring-brand-500 text-sm font-mono"
                  required
                />
              </div>

              <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-100">
                <button
                  type="button"
                  onClick={() => setIsPurchaseSeatOpen(false)}
                  disabled={isSubmittingSeat}
                  className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 hover:text-slate-800 hover:bg-slate-100 transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={isSubmittingSeat}
                  className="px-5 py-2 rounded-xl text-sm font-semibold text-white bg-brand-600 hover:bg-brand-700 shadow-md shadow-brand-500/20 disabled:opacity-50 transition-colors"
                >
                  {isSubmittingSeat ? 'Submitting...' : 'Submit Payment for Verification'}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Edit Branches Modal */}
      {isEditBranchesOpen && selectedStaff && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-slate-900/60 backdrop-blur-sm animate-in fade-in duration-150">
          <div className="bg-white rounded-2xl max-w-md w-full p-6 shadow-2xl border border-slate-100 max-h-[90vh] overflow-y-auto">
            <div className="flex items-center justify-between pb-4 border-b border-slate-100">
              <div className="flex items-center gap-2">
                <div className="w-10 h-10 rounded-xl bg-brand-50 text-brand-600 flex items-center justify-center">
                  <Building2 className="w-5 h-5" />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-slate-900">Assigned Branches</h3>
                  <p className="text-xs text-slate-500">{selectedStaff.fullName}</p>
                </div>
              </div>
              <button
                onClick={() => {
                  setIsEditBranchesOpen(false);
                  setSelectedStaff(null);
                }}
                className="w-9 h-9 rounded-xl flex items-center justify-center text-slate-400 hover:text-slate-600 hover:bg-slate-100 transition-colors"
              >
                <X className="w-5 h-5" />
              </button>
            </div>

            <form onSubmit={handleEditBranchesSubmit} className="mt-5 space-y-4">
              <div className="space-y-2 max-h-60 overflow-y-auto border border-slate-200 rounded-xl p-3 bg-slate-50/50">
                {branches.map((b) => {
                  const checked = selectedBranchIds.includes(b.id);
                  return (
                    <label key={b.id} className="flex items-center gap-2.5 cursor-pointer text-xs">
                      <input
                        type="checkbox"
                        checked={checked}
                        onChange={(e) => {
                          if (e.target.checked) {
                            setSelectedBranchIds([...selectedBranchIds, b.id]);
                          } else {
                            setSelectedBranchIds(selectedBranchIds.filter((id) => id !== b.id));
                          }
                        }}
                        className="w-4 h-4 rounded border-slate-300 text-brand-600 focus:ring-brand-500"
                      />
                      <span className="font-semibold text-slate-800">{b.branchName || b.name}</span>
                      <span className="text-slate-400">({b.category})</span>
                    </label>
                  );
                })}
              </div>

              <div className="flex items-center justify-end gap-3 pt-3 border-t border-slate-100">
                <button
                  type="button"
                  onClick={() => {
                    setIsEditBranchesOpen(false);
                    setSelectedStaff(null);
                  }}
                  className="px-4 py-2 rounded-xl text-sm font-medium text-slate-600 hover:text-slate-800 hover:bg-slate-100 transition-colors"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  className="px-5 py-2 rounded-xl text-sm font-semibold text-white bg-brand-600 hover:bg-brand-700 shadow-md shadow-brand-500/20 transition-colors"
                >
                  Save Changes
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
};

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useParkioSdk } from '@/app/AppRuntimeContext';
import {
  createCancelParkingSessionMutationOptions,
  createCompleteParkingSessionMutationOptions,
  createConfirmActiveParkingSessionMutationOptions,
  createDeleteParkingSessionHistoryMutationOptions,
  createDeleteParkingSessionMutationOptions,
  createStartParkingSessionMutationOptions,
} from '@/data/mutation-options/parkingSession';

export function useStartParkingSessionMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createStartParkingSessionMutationOptions(sdk, queryClient));
}

export function useCompleteParkingSessionMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createCompleteParkingSessionMutationOptions(sdk, queryClient));
}

export function useConfirmActiveParkingSessionMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createConfirmActiveParkingSessionMutationOptions(sdk, queryClient));
}

export function useCancelParkingSessionMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createCancelParkingSessionMutationOptions(sdk, queryClient));
}

export function useDeleteParkingSessionMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createDeleteParkingSessionMutationOptions(sdk, queryClient));
}

export function useDeleteParkingSessionHistoryMutation() {
  const sdk = useParkioSdk();
  const queryClient = useQueryClient();
  return useMutation(createDeleteParkingSessionHistoryMutationOptions(sdk, queryClient));
}

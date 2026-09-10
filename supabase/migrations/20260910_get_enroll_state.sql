-- Applied live 2026-09-10 (project ihgmqwzdpugdzddobhbc).
--
-- WHY: cf.enroll_device_impl deliberately answers NULL both when it refuses (inactive, blocked,
-- build too old, not seen recently, Worker down) and when the Worker says `already_enrolled`.
-- That is right for enrol itself (a refusal must not leak which gate failed), but an app that
-- holds no token cannot tell «the server refuses me» from «the server thinks I am enrolled and I
-- lost my copy» — and only the second case has a fix the driver can perform: re-type the
-- activation code (activate_device → Worker `bySerial` rotation). The controller hit exactly this
-- after `pm clear`: activation lives outside the app's data, so the car stayed "activated", no
-- activation screen was reachable, and the bar just said «الخدمة الصوتية غير جاهزة».
--
-- This is a NEW read-only function under a new name (never widen enroll_device's signature —
-- see the `report_device_mode` overload scar). It reveals only whether a (car, app) row exists,
-- which the car's own settings screen already shows; no token material is exposed.
--
-- Answers: 'unknown' | 'blocked' | 'inactive' | 'enrolled' | 'not_enrolled'.
-- Client rule: token missing locally AND get_enroll_state = 'enrolled' → show the activation
-- code prompt; 'not_enrolled' → call enroll_device as usual; anything else → normal gates.

create or replace function public.get_enroll_state(device_hw_id text, app_id text default 'wallpapers')
returns text
language plpgsql
stable
security definer
set search_path to 'public'
as $function$
declare
    hw    text;
    v_app text := lower(coalesce(nullif(btrim(app_id), ''), 'wallpapers'));
    d     public.devices;
begin
    if device_hw_id is null or device_hw_id = '' then return 'unknown'; end if;
    hw := public.resolve_device_id(device_hw_id);
    select * into d from public.devices dv where dv.hardware_id = hw;
    if not found then return 'unknown'; end if;
    if d.is_blocked then return 'blocked'; end if;
    if not d.is_active then return 'inactive'; end if;
    if exists (select 1 from public.device_tokens t where t.hardware_id = hw and t.app_id = v_app) then
        return 'enrolled';
    end if;
    return 'not_enrolled';
end $function$;

revoke all on function public.get_enroll_state(text, text) from public;
grant execute on function public.get_enroll_state(text, text) to anon, authenticated;

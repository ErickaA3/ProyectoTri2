-- El protector de racha vence 24 horas después de comprarlo.
-- Cubre un día sin estudiar si estuvo activo en algún momento de ese día.
-- Se aplica ANTES de desplegar el código que lee streak_shield_until.

ALTER TABLE public.user_stats
    ADD COLUMN IF NOT EXISTS streak_shield_until timestamp with time zone;

-- Los protectores que ya estaban activos no tienen fecha de compra:
-- vencen 24 horas después de aplicar esta migración.
UPDATE public.user_stats
   SET streak_shield_until = now() + interval '24 hours'
 WHERE has_streak_shield AND streak_shield_until IS NULL;

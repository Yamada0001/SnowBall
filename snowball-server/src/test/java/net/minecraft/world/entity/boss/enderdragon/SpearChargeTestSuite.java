package net.minecraft.world.entity.boss.enderdragon;

import com.mojang.datafixers.util.Either;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.phases.DragonPhaseInstance;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhaseManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.item.component.KineticWeapon;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;
import org.snowballmc.snowball.LeavesConfig;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Suite
@SelectClasses(SpearChargeTest.class)
@ConfigurationParameter(key = "TestSuite", value = "VanillaFeature")
public class SpearChargeTestSuite {
}

@VanillaFeature
class SpearChargeTest {
    private static final Vec3 FORWARD = new Vec3(1, 0, 0);
    private boolean originalCooldown;
    private boolean originalHitPart;
    private ServerLevel level;
    private LivingEntity attacker;
    private ItemStack stack;
    private KineticWeapon weapon;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        originalCooldown = LeavesConfig.fix.spearChargeContactCooldown;
        originalHitPart = LeavesConfig.fix.spearChargeDragonHitPart;
        LeavesConfig.fix.spearChargeContactCooldown = false;
        LeavesConfig.fix.spearChargeDragonHitPart = false;
        level = mock(ServerLevel.class);
        attacker = mock(LivingEntity.class);
        stack = mock(ItemStack.class);
        weapon = new KineticWeapon(10, 0, Optional.empty(), Optional.empty(),
            KineticWeapon.Condition.ofRelativeSpeed(20, 5.0F), 0.0F, 0.0F, Optional.empty(), Optional.empty());
        when(attacker.level()).thenReturn(level);
        when(attacker.getEyePosition()).thenReturn(Vec3.ZERO);
        when(attacker.getLookAngle()).thenReturn(FORWARD);
        when(attacker.getKnownSpeed()).thenReturn(Vec3.ZERO);
        when(attacker.getAttributeBaseValue(Attributes.ATTACK_DAMAGE)).thenReturn(12.0);
        when(attacker.getAttackRangeWith(stack)).thenReturn(AttackRange.CODEC_DEFAULT);
        when(stack.getUseDuration(attacker)).thenReturn(100);
        setField(LivingEntity.class, attacker, "recentKineticEnemies", new Object2LongOpenHashMap<Entity>());
        doCallRealMethod().when(attacker).rememberStabbedEntity(any());
        when(attacker.wasRecentlyStabbed(any(), anyInt())).thenCallRealMethod();
        when(attacker.stabbedEntities(any())).thenCallRealMethod();
    }

    @AfterEach
    void restoreConfig() {
        LeavesConfig.fix.spearChargeContactCooldown = originalCooldown;
        LeavesConfig.fix.spearChargeDragonHitPart = originalHitPart;
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void slowContactOnlyConsumesVanillaCooldown(boolean fix) {
        LeavesConfig.fix.spearChargeContactCooldown = fix;
        LivingEntity target = mock(LivingEntity.class);
        when(target.getKnownSpeed()).thenReturn(Vec3.ZERO);
        try (MockedStatic<ProjectileUtil> ray = mockStatic(ProjectileUtil.class)) {
            hits(ray, new EntityHitResult(target, FORWARD));
            when(level.getGameTime()).thenReturn(1L);
            weapon.damageEntities(stack, 99, attacker, EquipmentSlot.MAINHAND);
            assertEquals(fix ? 0 : 1, attacker.stabbedEntities(e -> true));
            verify(attacker, never()).stabAttack(EquipmentSlot.MAINHAND, target, 12.0F, true, false, false);

            when(attacker.getKnownSpeed()).thenReturn(FORWARD);
            when(level.getGameTime()).thenReturn(2L);
            weapon.damageEntities(stack, 98, attacker, EquipmentSlot.MAINHAND);
            when(level.getGameTime()).thenReturn(3L);
            weapon.damageEntities(stack, 97, attacker, EquipmentSlot.MAINHAND);
            if (fix) {
                verify(attacker).stabAttack(EquipmentSlot.MAINHAND, target, 12.0F, true, false, false);
            } else {
                verify(attacker, never()).stabAttack(EquipmentSlot.MAINHAND, target, 12.0F, true, false, false);
            }
        }
    }

    @Test
    void nearestDragonPartReceivesDamageButOnlyTheParentUsesCooldown() throws ReflectiveOperationException {
        LeavesConfig.fix.spearChargeDragonHitPart = true;
        EnderDragon dragon = dragon();
        EnderDragonPart head = part(dragon);
        EnderDragonPart neck = part(dragon);
        EnderDragonPart body = part(dragon);
        setField(EnderDragon.class, dragon, "head", head);
        setField(EnderDragon.class, dragon, "neck", neck);
        setField(EnderDragon.class, dragon, "body", body);
        DamageSource source = mock(DamageSource.class);
        when(source.getEntity()).thenReturn(mock(Player.class));
        when(stack.getDamageSource(attacker)).thenReturn(source);
        when(stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY)).thenReturn(ItemEnchantments.EMPTY);
        when(attacker.getItemBySlot(EquipmentSlot.MAINHAND)).thenReturn(stack);
        when(dragon.getItemBySlot(any())).thenReturn(ItemStack.EMPTY);
        when(dragon.hurt(any(), any(), any(), anyFloat())).thenCallRealMethod();
        when(dragon.hurtServer(any(), any(), anyFloat())).thenCallRealMethod();
        doCallRealMethod().when(attacker).stabAttack(any(), any(), anyFloat(), eq(true), eq(false), eq(false), any());
        doCallRealMethod().when(attacker).stabAttack(any(), any(), anyFloat(), eq(true), eq(false), eq(false));
        when(attacker.getKnownSpeed()).thenReturn(FORWARD);

        try (MockedStatic<ProjectileUtil> ray = mockStatic(ProjectileUtil.class)) {
            hits(ray, new EntityHitResult(body, FORWARD.scale(4)), new EntityHitResult(head, FORWARD.scale(2)));
            when(level.getGameTime()).thenReturn(1L);
            weapon.damageEntities(stack, 99, attacker, EquipmentSlot.MAINHAND);
            assertEquals(1, attacker.stabbedEntities(e -> e == dragon));
            assertEquals(1, attacker.stabbedEntities(e -> true));
            verify(dragon).reallyHurt(level, source, 12.0F);
            verify(dragon, never()).hurt(level, body, source, 12.0F);

            hits(ray, new EntityHitResult(neck, FORWARD.scale(2)));
            when(level.getGameTime()).thenReturn(2L);
            weapon.damageEntities(stack, 98, attacker, EquipmentSlot.MAINHAND);
            verify(dragon, never()).hurt(level, neck, source, 12.0F);

            when(level.getGameTime()).thenReturn(11L);
            weapon.damageEntities(stack, 89, attacker, EquipmentSlot.MAINHAND);
            verify(dragon).hurt(level, neck, source, 12.0F);

            hits(ray, new EntityHitResult(body, FORWARD.scale(2)));
            when(level.getGameTime()).thenReturn(21L);
            weapon.damageEntities(stack, 87, attacker, EquipmentSlot.MAINHAND);
            verify(dragon).reallyHurt(level, source, 4.0F);

            LeavesConfig.fix.spearChargeDragonHitPart = false;
            hits(ray, new EntityHitResult(head, FORWARD.scale(2)));
            when(level.getGameTime()).thenReturn(31L);
            weapon.damageEntities(stack, 86, attacker, EquipmentSlot.MAINHAND);
            verify(dragon).hurtServer(level, source, 12.0F);
        }
    }

    private void hits(MockedStatic<ProjectileUtil> ray, EntityHitResult... hits) {
        ray.when(() -> ProjectileUtil.getHitEntitiesAlong(eq(attacker), eq(AttackRange.CODEC_DEFAULT), any(), eq(ClipContext.Block.COLLIDER)))
            .thenReturn(Either.right(List.of(hits)));
    }

    private static EnderDragon dragon() throws ReflectiveOperationException {
        EnderDragon dragon = mock(EnderDragon.class);
        EnderDragonPhaseManager manager = mock(EnderDragonPhaseManager.class);
        DragonPhaseInstance phase = mock(DragonPhaseInstance.class);
        when(manager.getCurrentPhase()).thenReturn(phase);
        doReturn(EnderDragonPhase.HOVERING).when(phase).getPhase();
        when(phase.onHurt(any(), anyFloat())).thenAnswer(call -> call.getArgument(1));
        setField(EnderDragon.class, dragon, "phaseManager", manager);
        when(dragon.getKnownSpeed()).thenReturn(Vec3.ZERO);
        return dragon;
    }

    private static EnderDragonPart part(EnderDragon dragon) throws ReflectiveOperationException {
        EnderDragonPart part = mock(EnderDragonPart.class);
        setField(EnderDragonPart.class, part, "parentMob", dragon);
        when(part.hurtServer(any(), any(), anyFloat())).thenCallRealMethod();
        return part;
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

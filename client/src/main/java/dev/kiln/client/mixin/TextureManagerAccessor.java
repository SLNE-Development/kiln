package dev.kiln.client.mixin;

import java.util.Map;

import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Registered textures by id, to refuse extension textures that would replace an existing one. */
@Mixin(TextureManager.class)
public interface TextureManagerAccessor {
	@Accessor("byPath")
	Map<Identifier, AbstractTexture> kiln$getByPath();
}

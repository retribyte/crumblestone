package space.retri.crumblestone;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;

public class CrumblestoneRecipe {
    private static List<ShapedRecipe> recipes = new ArrayList<ShapedRecipe>();

    // blacklisted recipes e.g. dirtxgravel = coarse dirt
    private static final List<List<String>> BLACKLISTED_PAIRS = List.of(
        List.of("DIRT", "GRAVEL")
    );

    private static boolean isBlacklisted(String a, String b) {
        for (List<String> pair : BLACKLISTED_PAIRS) {
            if ((pair.get(0).equalsIgnoreCase(a) && pair.get(1).equalsIgnoreCase(b))
                || (pair.get(0).equalsIgnoreCase(b) && pair.get(1).equalsIgnoreCase(a)))
                return true;
        }
        return false;
    }

    public static List<ShapedRecipe> createRecipes(List<String> ingredients, ItemStack result) {
        result.setAmount(8);
        
        for (int i = 0; i < ingredients.size(); i++) {
            for (int j = 0; j < ingredients.size(); j++) {
                // should skip duplicates i.e. no dirtxdirt
                if (i == j)
                    continue;

                if (isBlacklisted(ingredients.get(i), ingredients.get(j)))
                    continue;
                
                // blacklist using the crumblestone material in the crafting recipe
                // should prevent infinite crumblestone
                if (ingredients.get(i).equalsIgnoreCase(CrumblestonePlugin.getMaterial().toString())
                    || ingredients.get(j).equalsIgnoreCase(CrumblestonePlugin.getMaterial().toString()))
                    continue;

                ShapedRecipe recipe = new ShapedRecipe(
                    new NamespacedKey(
                        CrumblestonePlugin.getPlugin(), "crumblestone_" + ingredients.get(i) + '_' + ingredients.get(j)
                    ), result
                );

                recipe.shape("XY", "YX");
                recipe.setIngredient('X', Material.valueOf(ingredients.get(i)));
                recipe.setIngredient('Y', Material.valueOf(ingredients.get(j)));
                recipe.setCategory(CraftingBookCategory.BUILDING);
                recipe.setGroup("crumblestone");

                recipes.add(recipe);
                // CrumblestonePlugin.getPlugin().getLogger().info("Added recipe: crumblestone_" + ingredients.get(i) + '_' + ingredients.get(j));
            }
        }
        
        return recipes;
    }
}
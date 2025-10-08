package space.retri.minecraft.crumblestone;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.recipe.CraftingBookCategory;

public class CrumblestoneRecipe {
    private static List<ShapedRecipe> recipes = new ArrayList<ShapedRecipe>();

    public static List<ShapedRecipe> createRecipes(List<String> ingredients, ItemStack result) {
        result.setAmount(8);
        
        for (int i = 0; i < ingredients.size(); i++) {
            for (int j = 0; j < ingredients.size(); j++) {
                // should skip duplicates i.e. no dirtxdirt
                if (i == j)
                    continue;

                // blacklisted recipes e.g. dirtxgravel = coarse dirt
                // should probably check dynamically in the future
                // if that is even possible
                if ((ingredients.get(i).equalsIgnoreCase("dirt") 
                    && ingredients.get(j).equalsIgnoreCase("gravel")))
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
                CrumblestonePlugin.getPlugin().getLogger().info("Added recipe: crumblestone_" + ingredients.get(i) + '_' + ingredients.get(j));
            }
        }
        
        return recipes;
    }
}
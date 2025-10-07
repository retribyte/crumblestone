package space.retri.minecraft.crumblestone;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

public class CrumblestoneRecipe {
    private static List<ShapedRecipe> recipes = new ArrayList<ShapedRecipe>();

    public static List<ShapedRecipe> createRecipes(List<String> ingredients, ItemStack result) {
        result.setAmount(8);
        
        for (int i = 0; i < ingredients.size(); i++) {
            for (int j = i; j < ingredients.size(); j++) {
                ShapedRecipe recipe = new ShapedRecipe(
                    new NamespacedKey(
                        CrumblestonePlugin.getPlugin(), "crumblestone_" + ingredients.get(i) + '_' + ingredients.get(j)
                    ), result
                );

                recipe.shape("XY", "YX");
                recipe.setIngredient('X', Material.valueOf(ingredients.get(i)));
                recipe.setIngredient('Y', Material.valueOf(ingredients.get(j)));

                recipes.add(recipe);
            }
        }
        
        return recipes;
    }
}
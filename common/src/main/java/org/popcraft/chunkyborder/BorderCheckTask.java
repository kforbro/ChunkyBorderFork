package org.popcraft.chunkyborder;

import org.popcraft.chunky.platform.Player;
import org.popcraft.chunky.platform.util.Location;
import org.popcraft.chunky.platform.util.Vector2;
import org.popcraft.chunky.platform.util.Vector3;
import org.popcraft.chunky.shape.AbstractEllipse;
import org.popcraft.chunky.shape.AbstractPolygon;
import org.popcraft.chunky.shape.Shape;
import org.popcraft.chunky.shape.ShapeType;
import org.popcraft.chunky.shape.ShapeUtil;
import org.popcraft.chunkyborder.event.border.BorderWrapEvent;

import java.util.ArrayList;
import java.util.List;

public class BorderCheckTask implements Runnable {
    private final ChunkyBorder chunkyBorder;

    public BorderCheckTask(final ChunkyBorder chunkyBorder) {
        this.chunkyBorder = chunkyBorder;
    }

    @Override
    public void run() {
        for (final Player player : chunkyBorder.getChunky().getServer().getPlayers()) {
            final PlayerData playerData = chunkyBorder.getPlayerData(player.getUUID());
            chunkyBorder.getBorder(player.getWorld().getName()).ifPresent(borderData -> {
                final Location location = player.getLocation();
                if (borderData.getBorder().isBounding(location.getX(), location.getZ())) {
                    playerData.setLastLocation(location);
                } else if (!player.hasPermission("chunkyborder.bypass.move") && !playerData.isBypassing()) {
                    final Location redirect;
                    final BorderWrapType borderWrapType = borderData.getWrapType();
                    if (!BorderWrapType.NONE.equals(borderWrapType)) {
                        redirect = wrap(borderData, borderWrapType, player, playerData);
                        playerData.setLastLocation(redirect);
                        chunkyBorder.getChunky().getEventBus().call(new BorderWrapEvent(player, location, redirect));
                    } else {
                        redirect = playerData.getLastLocation().orElse(location.getWorld().getSpawn());
                        redirect.setYaw(location.getYaw());
                        redirect.setPitch(location.getPitch());
                    }
                    location.getWorld().playEffect(player, chunkyBorder.getConfig().effect());
                    location.getWorld().playSound(player, chunkyBorder.getConfig().sound());
                    player.teleport(redirect);
                    if (chunkyBorder.getConfig().hasMessage()) {
                        if (chunkyBorder.getConfig().useActionBar()) {
                            player.sendActionBar("custom_border_message");
                        } else {
                            player.sendMessage("custom_border_message");
                        }
                    }
                }
            });
        }
    }

    private Location wrap(final BorderData borderData, final BorderWrapType borderWrapType, final Player player, final PlayerData playerData) {
        final Location location = player.getLocation();
        final boolean rectangle = ShapeType.SQUARE.equals(borderData.getShape()) || ShapeType.RECTANGLE.equals(borderData.getShape());
        final boolean wrapped = switch (borderWrapType) {
            case NONE -> false;
            case DEFAULT -> rectangle ? wrapBoth(borderData, location) : wrapRadial(borderData, location);
            case BOTH -> wrapBoth(borderData, location);
            case RADIAL -> wrapRadial(borderData, location);
            case X -> wrapX(borderData, location);
            case Z -> wrapZ(borderData, location);
            case EARTH -> rectangle && wrapEarth(borderData, location);
        };
        if (wrapped) {
            final int elevation = location.getWorld().getElevation((int) location.getX(), (int) location.getZ());
            if (elevation >= location.getWorld().getMaxElevation()) {
                return location.getWorld().getSpawn();
            }
            location.setY(elevation);
        } else {
            final Location lastLocation = playerData.getLastLocation().orElse(location.getWorld().getSpawn());
            location.setX(lastLocation.getX());
            location.setY(lastLocation.getY());
            location.setZ(lastLocation.getZ());
        }
        return location;
    }

    private boolean wrapBoth(final BorderData borderData, final Location location) {
        wrapX(borderData, location);
        wrapZ(borderData, location);
        return true;
    }

    private boolean wrapRadial(final BorderData borderData, final Location location) {
        final Vector2 center = Vector2.of(borderData.getCenterX(), borderData.getCenterZ());
        final Vector2 from = Vector2.of(location.getX(), location.getZ());
        final Shape border = borderData.getBorder();
        final List<Vector2> intersections = new ArrayList<>();
        if (border instanceof final AbstractPolygon polygon) {
            final List<Vector2> points = polygon.points();
            final int size = points.size();
            for (int i = 0; i < size; ++i) {
                final Vector2 p1 = points.get(i);
                final Vector2 p2 = points.get(i == size - 1 ? 0 : i + 1);
                ShapeUtil.intersection(center.getX(), center.getZ(), from.getX(), from.getZ(), p1.getX(), p1.getZ(), p2.getX(), p2.getZ()).ifPresent(intersections::add);
            }
        } else if (border instanceof final AbstractEllipse ellipse) {
            final Vector2 radii = ellipse.radii();
            final double angle = Math.PI + Math.atan2(from.getZ() - center.getX(), from.getX() - center.getZ());
            intersections.add(ShapeUtil.pointOnEllipse(center.getX(), center.getZ(), radii.getX(), radii.getZ(), angle));
        }
        if (intersections.isEmpty()) {
            return false;
        }
        final Vector3 centerDirection = new Vector3(from.getX() - center.getX(), 0, from.getZ() - center.getZ()).normalize().multiply(3);
        Vector2 closest = intersections.get(0);
        double longestDistance = Double.MIN_VALUE;
        for (final Vector2 intersection : intersections) {
            final double distance = from.distanceSquared(intersection);
            if (distance > longestDistance && border.isBounding(intersection.getX() + centerDirection.getX(), intersection.getZ() + centerDirection.getZ())) {
                closest = intersection;
                longestDistance = distance;
            }
        }
        if (longestDistance == Double.MIN_VALUE) {
            return false;
        }
        location.setX(closest.getX());
        location.setZ(closest.getZ());
        location.add(centerDirection);
        location.setDirection(centerDirection);
        return true;
    }

    private boolean wrapX(final BorderData borderData, final Location location) {
        final double minX = borderData.getCenterX() - borderData.getRadiusX();
        final double maxX = borderData.getCenterX() + borderData.getRadiusX();
        if (location.getX() <= minX) {
            location.setX(maxX - 3);
        } else if (location.getX() >= maxX) {
            location.setX(minX + 3);
        }
        final double minZ = borderData.getCenterZ() - borderData.getRadiusZ();
        final double maxZ = borderData.getCenterZ() + borderData.getRadiusZ();
        return location.getZ() > minZ && location.getZ() < maxZ;
    }

    private boolean wrapZ(final BorderData borderData, final Location location) {
        final double minZ = borderData.getCenterZ() - borderData.getRadiusZ();
        final double maxZ = borderData.getCenterZ() + borderData.getRadiusZ();
        if (location.getZ() <= minZ) {
            location.setZ(maxZ - 3);
        } else if (location.getZ() >= maxZ) {
            location.setZ(minZ + 3);
        }
        final double minX = borderData.getCenterX() - borderData.getRadiusX();
        final double maxX = borderData.getCenterX() + borderData.getRadiusX();
        return location.getX() > minX && location.getX() < maxX;
    }

    private boolean wrapEarth(final BorderData borderData, final Location location) {
        wrapX(borderData, location);
        final double minZ = borderData.getCenterZ() - borderData.getRadiusZ();
        final double maxZ = borderData.getCenterZ() + borderData.getRadiusZ();
        final double centerX = borderData.getCenterX();
        final double meridianDistance = centerX - location.getX();
        if (location.getZ() <= minZ) {
            location.setX(centerX + meridianDistance);
            location.setZ(minZ + 3);
            location.setYaw(0);
        } else if (location.getZ() >= maxZ) {
            location.setX(centerX + meridianDistance);
            location.setZ(maxZ - 3);
            location.setYaw(180);
        }
        return true;
    }
}
